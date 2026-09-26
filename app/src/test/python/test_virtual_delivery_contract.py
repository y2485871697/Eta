"""Non-build checks; these do not execute Android/Room or replace Kotlin tests."""
from contextlib import closing
import pathlib
import re
import sqlite3
import unittest
import xml.etree.ElementTree as ET

APP = pathlib.Path(__file__).resolve().parents[3]
MAIN = APP / 'src/main'
DB = MAIN / 'kotlin/io/github/mangi/eta/data/db'

class VirtualDeliveryMigrationSqlTest(unittest.TestCase):
    def test_additive_migration_preserves_rows_and_defaults_false(self):
        source = (DB / 'EtaDatabase.kt').read_text()
        start = source.index('internal val MIGRATION_28_29')
        end = source.index('internal val MIGRATION_27_28', start)
        statements = re.findall(r'"(ALTER TABLE [^"]+)"', source[start:end])
        self.assertEqual(2, len(statements))
        with closing(sqlite3.connect(':memory:')) as db:
            for table in ('runtime_results', 'runtime_archive_runs'):
                db.execute(f'CREATE TABLE {table} (id TEXT PRIMARY KEY, content TEXT, transcript_json TEXT)')
                db.execute(f'INSERT INTO {table} VALUES (?, ?, ?)', ('old', '原回答', '[]'))
            for statement in statements:
                db.execute(statement)
            for table in ('runtime_results', 'runtime_archive_runs'):
                row = db.execute(f'SELECT content, transcript_json, virtual_delivery_completed FROM {table}').fetchone()
                self.assertEqual(('原回答', '[]', 0), row)
                info = {item[1]: item for item in db.execute(f'PRAGMA table_info({table})')}
                field = info['virtual_delivery_completed']
                self.assertEqual(('INTEGER', 1, '0'), (field[2], field[3], field[4]))
                db.execute(f'INSERT INTO {table} VALUES (?, ?, ?, ?)', ('new', '已交接', '[]', 1))
                self.assertEqual(1, db.execute(f'SELECT virtual_delivery_completed FROM {table} WHERE id=?', ('new',)).fetchone()[0])

    def test_room_schema_declarations_match_migration_default(self):
        entities = (DB / 'RuntimeRunEntities.kt').read_text()
        self.assertEqual(2, entities.replace(' ', '').count('@ColumnInfo(name="virtual_delivery_completed",defaultValue="0")'))
        self.assertEqual(2, entities.count('val virtualDeliveryCompleted: Boolean = false'))
        database = (DB / 'EtaDatabase.kt').read_text()
        self.assertIn('version = 29,', database)
        self.assertEqual(2, database.count('MIGRATION_28_29'))

    def test_completed_label_is_unique_in_all_supported_locales(self):
        expected = {'values': 'Completed', 'values-b+zh+Hans': '已完成', 'values-b+zh+Hant': '已完成'}
        for folder, text in expected.items():
            root = ET.parse(MAIN / 'res' / folder / 'strings.xml').getroot()
            matches = [node for node in root.findall('string') if node.get('name') == 'system_notice_completed']
            self.assertEqual(1, len(matches), folder)
            self.assertEqual(text, ''.join(matches[0].itertext()), folder)

if __name__ == '__main__':
    unittest.main()
