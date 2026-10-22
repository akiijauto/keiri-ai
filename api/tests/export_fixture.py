import unittest
import httpx
import main


def entry(debit=100, credit=100):
    return dict(debit_account='消耗品費', debit_amount=debit,
                credit_account='未払金', credit_amount=credit,
                tax_code='対象外', description='合成の検査')


class ExportCase(unittest.IsolatedAsyncioTestCase):
    async def request(self, entries, **fields):
        transport = httpx.ASGITransport(app=main.app)
        async with httpx.AsyncClient(transport=transport, base_url='http://synthetic.invalid') as client:
            return await client.post('/export/csv', json=dict(entries=entries, **fields))
