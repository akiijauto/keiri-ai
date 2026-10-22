from unittest.mock import patch
from export_fixture import ExportCase, entry
import main


class BalanceExportContract(ExportCase):
    async def check_rejected(self, entries):
        with patch.object(main.freee_csv, 'build_csv', wraps=main.freee_csv.build_csv) as writer:
            response = await self.request(entries)
            self.assertEqual(response.status_code, 400)
            self.assertIn('貸借不一致', response.json()['detail'])
            writer.assert_not_called()

    async def test_debit_excess(self):
        await self.check_rejected([entry(100, 90)])

    async def test_credit_excess(self):
        await self.check_rejected([entry(90, 100)])

    async def test_one_unbalanced_row_in_many(self):
        await self.check_rejected([entry(), entry(50, 40)])

    async def test_totals_differ_even_when_rows_offset_partly(self):
        await self.check_rejected([entry(100, 90), entry(50, 70)])
