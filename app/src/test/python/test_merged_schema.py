# SQL/source checks only; not a substitute for Room or device migration tests.
from contextlib import closing
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[3]
DB = ROOT / 'src/main/kotlin/io/github/mangi/eta/data/db/EtaDatabase.kt'

class MergedSchemaTest(unittest.TestCase):
    def setUp(self):
        self.source = DB.read_text()
        block = self.source.split('internal val MIGRATION_29_30 =', 1)[1]
        block = block.split('internal val MIGRATION_27_28', 1)[0]
        self.fields = [line.split(chr(34))[1::2] for line in block.splitlines()
                       if line.strip().startswith('addColumnIfMissing(')]
        self.assertEqual(3, len(self.fields))
        self.assertIn('if (!tableHasColumn(database, table, column))', block)

    def seed(self, db):
        for table, _, _ in self.fields:
            db.execute(f'CREATE TABLE {table} (id TEXT PRIMARY KEY, content TEXT)')
            db.execute(f'INSERT INTO {table} VALUES (?, ?)', ('old', 'preserved'))

    def migrate(self, db):
        for table, column, definition in self.fields:
            if column not in [r[1] for r in db.execute(f'PRAGMA table_info({table})')]:
                db.execute(f'ALTER TABLE {table} ADD COLUMN {column} {definition}')

    def test_all_v29_column_variants_preserve_data(self):
        for mask in range(8):
            with self.subTest(mask=mask), closing(sqlite3.connect(':memory:')) as db:
                self.seed(db)
                expected = []
                for index, (table, column, definition) in enumerate(self.fields):
                    present = bool(mask & (1 << index))
                    value = 'saved-receipt' if index == 0 else 1
                    if present:
                        db.execute(f'ALTER TABLE {table} ADD COLUMN {column} {definition}')
                        db.execute(f'UPDATE {table} SET {column}=?', (value,))
                    expected.append(value if present else ('' if index == 0 else 0))
                self.migrate(db)
                self.migrate(db)
                for (table, column, _), value in zip(self.fields, expected):
                    row = db.execute(f'SELECT content, {column} FROM {table}').fetchone()
                    self.assertEqual(('preserved', value), row)

    def test_v28_cloud_then_delivery_upgrade(self):
        block = self.source.split('internal val MIGRATION_28_29 =', 1)[1]
        sql = block.split(chr(34), 2)[1]
        with closing(sqlite3.connect(':memory:')) as db:
            self.seed(db)
            db.execute(sql)
            self.migrate(db)
            for index, (table, column, _) in enumerate(self.fields):
                self.assertEqual(('', 0, 0)[index], db.execute(f'SELECT {column} FROM {table}').fetchone()[0])

if __name__ == '__main__':
    unittest.main()
