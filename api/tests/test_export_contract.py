from export_fixture import ExportCase, entry


class ExistingExportContract(ExportCase):
    async def test_balanced_csv(self):
        response = await self.request([entry()])
        self.assertEqual(response.status_code, 200)
        self.assertIn('100', response.content.decode('cp932'))

    async def test_japanese(self):
        response = await self.request([entry()], partner='合成取引先')
        self.assertIn('合成取引先', response.content.decode('cp932'))

    async def test_csv_headers(self):
        response = await self.request([entry()])
        self.assertTrue(response.content.decode('cp932').startswith('日付,借方勘定科目'))
        self.assertIn('attachment;', response.headers['content-disposition'])

    async def test_date_and_crlf(self):
        response = await self.request([entry()], date='2026-10-23')
        self.assertIn('2026/10/23', response.content.decode('cp932'))
        self.assertIn(b'\r\n', response.content)

    async def test_empty_rejected(self):
        response = await self.request([])
        self.assertEqual(response.status_code, 400)

    async def test_balanced_multiline(self):
        # The existing contract is equality of totals, not equality of every row.
        response = await self.request([entry(100, 80), entry(30, 50)])
        self.assertEqual(response.status_code, 200)
