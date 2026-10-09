"""UI wiring, invalidation and no-output-gating boundaries; behavior is tested in Kotlin."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class AsyncAssistantAvatarContract(unittest.TestCase):
    def test_both_avatar_composables_use_async_loader(self):
        avatar = (ROOT / 'ui/components/AssistantAvatar.kt').read_text()
        editor = (ROOT / 'ui/screens/assistants/AssistantEditScreen.kt').read_text()
        self.assertIn('rememberAssistantAvatarBitmap(assistant?.id, assistant?.avatarFileName)', avatar)
        self.assertIn('rememberAssistantAvatarBitmap(assistantId, avatarFileName)', editor)
        for text in (avatar, editor):
            self.assertNotIn('AssistantRepository.avatarBitmap(', text)
        self.assertIn('remember(assistantId, fileName, revision)', avatar)
        self.assertIn('LaunchedEffect(result, fileName, revision)', avatar)
        self.assertIn('AssistantRepository.avatarRevision.value == revision', avatar)

    def test_io_success_cache_is_bounded_and_does_not_recycle(self):
        text = (ROOT / 'data/repository/AssistantAvatarBitmapCache.kt').read_text()
        for token in ('withContext(Dispatchers.IO)', 'decodeMutex.withLock', '8 * 1024 * 1024',
                      'expectedGeneration != generation', 'bitmap == null', 'ensureActive()'):
            self.assertIn(token, text)
        self.assertNotIn('.recycle()', text)

    def test_repository_mutations_invalidate_fixed_file_names(self):
        text = (ROOT / 'data/repository/AssistantRepository.kt').read_text()
        for name in ('init(context:', 'delete(id:', 'importAvatars(files:', 'saveAvatar(id:',
                     'clearAvatar(id:', 'copyAvatar(sourceFileName:'):
            start = text.index('fun ' + name)
            end = text.find('\n    }', start)
            self.assertIn('invalidateAvatarBitmaps', text[start:end], name)
        self.assertIn('avatarCache.load(fileName, revision, ::avatarBitmap)', text)
        self.assertIn('@Synchronized\n    fun avatarBitmap', text)

    def test_append_has_terminal_owner_and_identity_guards(self):
        text = (ROOT / 'ui/components/AgentTimelineProjectionCache.kt').read_text()
        append = text.index('previous.size + 1 == messages.size')
        replacement = text.index('previous.size == messages.size')
        self.assertLess(append, replacement)
        for token in ('previous.none { it.id == appended.id }',
                      'previous.canAppendAssistantAfterTerminalOrdering(appended)',
                      'mapping.copyOf(messages.size)', 'nextMapping[previous.size] = entries.size'):
            self.assertIn(token, text)
        helper = (ROOT / 'ui/model/AgentTerminalMessageOrder.kt').read_text()
        self.assertIn('val owner = appended.ownerAmong(owners) ?: return true', helper)
        self.assertIn('it.ownerAmong(owners) == owner', helper)
