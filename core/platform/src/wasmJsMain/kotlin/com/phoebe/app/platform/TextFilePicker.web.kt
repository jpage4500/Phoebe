@file:OptIn(ExperimentalWasmJsInterop::class)

package com.phoebe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberTextFileSaver(): TextFileSaver =
    remember {
        TextFileSaver { suggestedFileName, text, onResult ->
            // Browsers download straight to the user's downloads folder (or ask, per their settings)
            // and don't report back, so a started download counts as saved.
            val error = webDownloadTextFile(suggestedFileName, text)
            onResult(if (error.isEmpty()) TextFileSaveResult.Saved else TextFileSaveResult.Failed(error))
        }
    }

@Composable
actual fun rememberTextFileOpener(): TextFileOpener =
    remember {
        TextFileOpener { onResult ->
            webOpenTextFile { status, value ->
                onResult(
                    when (status) {
                        "opened" -> TextFileOpenResult.Opened(value)
                        "cancelled" -> TextFileOpenResult.Cancelled
                        else -> TextFileOpenResult.Failed(value.ifBlank { null })
                    },
                )
            }
        }
    }

@JsFun(
    """
    (name, text) => {
      try {
        const blob = new Blob([text], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        link.style.display = 'none';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        return '';
      } catch (e) {
        return String((e && e.message) || e || 'Download failed');
      }
    }
    """,
)
private external fun webDownloadTextFile(name: String, text: String): String

@JsFun(
    """
    (onDone) => {
      try {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json,application/json,text/plain';
        input.style.display = 'none';
        document.body.appendChild(input);
        let finished = false;
        const finish = (status, value) => {
          if (finished) return;
          finished = true;
          if (input.parentNode) input.parentNode.removeChild(input);
          onDone(status, value);
        };
        input.addEventListener('cancel', () => finish('cancelled', ''));
        input.onchange = async () => {
          const file = (input.files || [])[0];
          if (!file) { finish('cancelled', ''); return; }
          try {
            finish('opened', await file.text());
          } catch (e) {
            finish('failed', String((e && e.message) || e || ''));
          }
        };
        input.click();
      } catch (e) {
        onDone('failed', String((e && e.message) || e || ''));
      }
    }
    """,
)
private external fun webOpenTextFile(onDone: (String, String) -> Unit)
