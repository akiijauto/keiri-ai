#!/usr/bin/env python3
"""通信監視の結果（pcap）を解析して、許可リスト外の通信先を洗い出す。

企画書 20「『コード上では通信していない』だけでは合格としない」への対応。
Phase 3 のネットワーク監視試験では、OCRフェーズを実行しながら取得した pcap を
このスクリプトに通し、許可リスト外の宛先が0件であることを合格条件とする。

依存ライブラリなし（標準ライブラリのみ）。tcpdump/Wireshark が出力する
libpcap 形式（マイクロ秒・ナノ秒、リトル/ビッグエンディアン）を読む。

使い方:
    python3 tools/analyze_pcap.py capture.pcap
    python3 tools/analyze_pcap.py capture.pcap --allow 203.0.113.10 --allow 198.51.100.0/24
    python3 tools/analyze_pcap.py capture.pcap --phase ocr    # OCRフェーズ = 宛先ゼロを要求

終了コード:
    0 = 許可リスト外の通信なし
    1 = 許可リスト外の通信あり（試験は不合格）
    2 = 入力が読めない
"""
from __future__ import annotations

import argparse
import ipaddress
import struct
import sys
from collections import Counter

PCAP_MAGIC = {
    0xA1B2C3D4: ("<", 1),        # microseconds, little endian
    0xD4C3B2A1: (">", 1),
    0xA1B23C4D: ("<", 1000),     # nanoseconds
    0x4D3CB2A1: (">", 1000),
}

LINKTYPE_ETHERNET = 1
LINKTYPE_RAW = 101
LINKTYPE_LINUX_SLL = 113
LINKTYPE_NULL = 0

# 端末内で完結する通信は「外部通信」ではない
ALWAYS_ALLOWED_NETWORKS = [
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("::1/128"),
]


class PcapError(Exception):
    pass


def read_packets(path):
    """(timestamp, ip_version, src, dst, protocol, dport) を順に返す。"""
    with open(path, "rb") as f:
        header = f.read(24)
        if len(header) < 24:
            raise PcapError("pcapヘッダが短すぎます")
        (magic,) = struct.unpack("<I", header[:4])
        if magic not in PCAP_MAGIC:
            (magic,) = struct.unpack(">I", header[:4])
        if magic not in PCAP_MAGIC:
            raise PcapError(
                "libpcap形式ではありません（pcapngの場合は "
                "`tcpdump -r in.pcapng -w out.pcap` などで変換してください）"
            )
        endian, _ = PCAP_MAGIC[magic]
        _, _, _, _, _, linktype = struct.unpack(endian + "HHiIII", header[4:24])

        while True:
            record = f.read(16)
            if len(record) < 16:
                return
            ts_sec, ts_frac, incl_len, _orig_len = struct.unpack(endian + "IIII", record)
            data = f.read(incl_len)
            if len(data) < incl_len:
                return
            parsed = parse_frame(data, linktype)
            if parsed:
                yield (ts_sec + ts_frac / 1_000_000.0,) + parsed


def parse_frame(data, linktype):
    if linktype == LINKTYPE_ETHERNET:
        if len(data) < 14:
            return None
        ethertype = struct.unpack("!H", data[12:14])[0]
        payload = data[14:]
        if ethertype == 0x0800:
            return parse_ipv4(payload)
        if ethertype == 0x86DD:
            return parse_ipv6(payload)
        return None

    if linktype == LINKTYPE_LINUX_SLL:
        if len(data) < 16:
            return None
        protocol = struct.unpack("!H", data[14:16])[0]
        payload = data[16:]
        if protocol == 0x0800:
            return parse_ipv4(payload)
        if protocol == 0x86DD:
            return parse_ipv6(payload)
        return None

    if linktype in (LINKTYPE_RAW, LINKTYPE_NULL):
        offset = 4 if linktype == LINKTYPE_NULL else 0
        payload = data[offset:]
        if not payload:
            return None
        version = payload[0] >> 4
        if version == 4:
            return parse_ipv4(payload)
        if version == 6:
            return parse_ipv6(payload)
    return None


def parse_ipv4(payload):
    if len(payload) < 20:
        return None
    ihl = (payload[0] & 0x0F) * 4
    protocol = payload[9]
    src = str(ipaddress.IPv4Address(payload[12:16]))
    dst = str(ipaddress.IPv4Address(payload[16:20]))
    return (4, src, dst, protocol, dest_port(payload[ihl:], protocol))


def parse_ipv6(payload):
    if len(payload) < 40:
        return None
    protocol = payload[6]
    src = str(ipaddress.IPv6Address(payload[8:24]))
    dst = str(ipaddress.IPv6Address(payload[24:40]))
    return (6, src, dst, protocol, dest_port(payload[40:], protocol))


def dest_port(transport, protocol):
    if protocol in (6, 17) and len(transport) >= 4:
        return struct.unpack("!H", transport[2:4])[0]
    return None


def protocol_name(number):
    return {1: "ICMP", 6: "TCP", 17: "UDP", 58: "ICMPv6"}.get(number, str(number))


def build_allowlist(entries):
    networks = list(ALWAYS_ALLOWED_NETWORKS)
    for entry in entries or []:
        networks.append(ipaddress.ip_network(entry, strict=False))
    return networks


def is_allowed(address, networks):
    addr = ipaddress.ip_address(address)
    return any(addr in net for net in networks)


def main(argv=None):
    parser = argparse.ArgumentParser(description="pcapから許可リスト外の通信先を検出する")
    parser.add_argument("pcap", help="tcpdump等で取得したlibpcapファイル")
    parser.add_argument("--allow", action="append", default=[],
                        help="許可する宛先アドレスまたはCIDR（複数指定可）")
    parser.add_argument("--phase", choices=["ocr", "web"], default="ocr",
                        help="ocr: 外部宛先ゼロを要求 / web: 許可リスト内のみ可")
    parser.add_argument("--allow-private", action="store_true",
                        help="社内LAN宛(RFC1918)を許可する")
    args = parser.parse_args(argv)

    networks = build_allowlist(args.allow)
    if args.allow_private:
        for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"):
            networks.append(ipaddress.ip_network(cidr))

    try:
        packets = list(read_packets(args.pcap))
    except (OSError, PcapError) as e:
        print(f"読み込みに失敗しました: {e}", file=sys.stderr)
        return 2

    violations = Counter()
    allowed = Counter()

    for _ts, _version, _src, dst, protocol, dport in packets:
        key = (dst, protocol_name(protocol), dport)
        if is_allowed(dst, networks):
            allowed[key] += 1
        else:
            violations[key] += 1

    print(f"解析パケット数: {len(packets)}")
    print(f"許可された宛先: {len(allowed)}種")
    for (dst, proto, port), count in sorted(allowed.items()):
        print(f"  OK   {dst:<40} {proto:<6} port={port} ({count}パケット)")

    if violations:
        print()
        print(f"許可リスト外の宛先: {len(violations)}種")
        for (dst, proto, port), count in sorted(violations.items()):
            print(f"  NG   {dst:<40} {proto:<6} port={port} ({count}パケット)")
        print()
        print("判定: 不合格。上記の宛先へ通信が発生しています。")
        return 1

    if args.phase == "ocr" and allowed:
        # OCRフェーズはループバック以外の通信も想定しない
        external = {k: v for k, v in allowed.items()
                    if not is_allowed(k[0], ALWAYS_ALLOWED_NETWORKS)}
        if external:
            print()
            print("判定: 要確認。OCRフェーズで端末外への通信が観測されました。")
            for (dst, proto, port), count in sorted(external.items()):
                print(f"  ?    {dst:<40} {proto:<6} port={port} ({count}パケット)")
            return 1

    print()
    print("判定: 合格。許可リスト外への通信は観測されませんでした。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
