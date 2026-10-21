package app.cmuxpocket.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import android.widget.EditText
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class TerminalInputView(
    context: Context
) : EditText(context) {

    var onInputText: (String?, String) -> Unit = { _, _ -> }
    var onDelete: (String?) -> Unit = {}
    var onEnter: (String?) -> Unit = {}

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var pendingKeyboardShow = false
    private var inputSurfaceId: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        // Preserve full CJK composing support with standard text type
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_NORMAL or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE

        imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                } else {
                    0
                }

        // Invisible anchor View that satisfies the system TextView contract
        background = null
        setTextColor(Color.TRANSPARENT)
        setHintTextColor(Color.TRANSPARENT)
        isCursorVisible = false
        setPadding(0, 0, 0, 0)
        alpha = 0f

        isLongClickable = false
        setTextIsSelectable(false)
        showSoftInputOnFocus = false

        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    fun requestKeyboard(surfaceId: String?) {
        inputSurfaceId = surfaceId
        pendingKeyboardShow = true

        if (!isAttachedToWindow) return

        if (!hasFocus()) {
            requestFocus()
        }

        showKeyboardWhenReady()
    }

    private fun showKeyboardWhenReady() {
        if (!pendingKeyboardShow) return

        post {
            if (!pendingKeyboardShow) return@post
            if (!isAttachedToWindow) return@post
            if (!hasFocus()) return@post
            if (!hasWindowFocus()) return@post

            pendingKeyboardShow = false

            val controller = ViewCompat.getWindowInsetsController(this)
            if (controller != null) {
                controller.show(WindowInsetsCompat.Type.ime())
            } else {
                imm.showSoftInput(this, 0)
            }
        }
    }

    fun hideKeyboard() {
        pendingKeyboardShow = false
        ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
            ?: imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun clearInputTarget() {
        inputSurfaceId = null
        hideKeyboard()
        clearLocalBuffer()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pendingKeyboardShow) {
            showKeyboardWhenReady()
        }
    }

    override fun onDetachedFromWindow() {
        pendingKeyboardShow = false
        editableText?.clear()
        super.onDetachedFromWindow()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null

        return object : InputConnectionWrapper(target, false) {

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val ok = super.commitText(text, newCursorPosition)
                if (ok && text.isNotEmpty()) {
                    onInputText(inputSurfaceId, text.toString())
                    clearLocalBuffer()
                }
                return ok
            }

            override fun finishComposingText(): Boolean {
                val editable = editableText
                val composingStart = BaseInputConnection.getComposingSpanStart(editable)
                val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)

                val pending = if (composingStart >= 0 && composingEnd >= 0 && composingStart != composingEnd) {
                    val start = minOf(composingStart, composingEnd)
                    val end = maxOf(composingStart, composingEnd)
                    editable.subSequence(start, end).toString()
                } else {
                    null
                }

                val ok = super.finishComposingText()
                if (ok && !pending.isNullOrEmpty()) {
                    onInputText(inputSurfaceId, pending)
                    clearLocalBuffer()
                }
                return ok
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // If local composition buffer exists, delete locally only
                if (editableText.isNotEmpty()) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                if (beforeLength > 0) {
                    repeat(beforeLength.coerceAtMost(64)) {
                        onDelete(inputSurfaceId)
                    }
                    return true
                }

                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (editableText.isNotEmpty()) {
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }

                if (beforeLength > 0) {
                    repeat(beforeLength.coerceAtMost(64)) {
                        onDelete(inputSurfaceId)
                    }
                    return true
                }

                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (editableText.isNotEmpty()) {
                    return super.sendKeyEvent(event)
                }

                if (event.action != KeyEvent.ACTION_DOWN) {
                    return true
                }

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        onDelete(inputSurfaceId)
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onEnter(inputSurfaceId)
                        return true
                    }
                }

                val codePoint = event.unicodeChar
                if (codePoint > 0 && Character.isValidCodePoint(codePoint)) {
                    onInputText(inputSurfaceId, String(Character.toChars(codePoint)))
                    return true
                }

                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                return when (editorAction) {
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_SEND -> {
                        onEnter(inputSurfaceId)
                        true
                    }
                    else -> super.performEditorAction(editorAction)
                }
            }
        }
    }

    private fun clearLocalBuffer() {
        editableText?.clear()
        if (text.isEmpty()) {
            setSelection(0)
        }
    }
}

@Composable
fun TerminalImeBridge(
    onSendText: (String?, String) -> Unit,
    onDelete: (String?) -> Unit,
    onEnter: (String?) -> Unit,
    onViewReady: (TerminalInputView?) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestSendText by rememberUpdatedState(onSendText)
    val latestDelete by rememberUpdatedState(onDelete)
    val latestEnter by rememberUpdatedState(onEnter)
    val latestViewReady by rememberUpdatedState(onViewReady)

    val holder = remember { arrayOfNulls<TerminalInputView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.hideKeyboard()
            holder[0] = null
            latestViewReady(null)
        }
    }

    AndroidView(
        factory = { context ->
            TerminalInputView(context).also { view ->
                holder[0] = view
                view.onInputText = { surfaceId, text -> latestSendText(surfaceId, text) }
                view.onDelete = { surfaceId -> latestDelete(surfaceId) }
                view.onEnter = { surfaceId -> latestEnter(surfaceId) }
                latestViewReady(view)
            }
        },
        update = { view ->
            view.onInputText = { surfaceId, text -> latestSendText(surfaceId, text) }
            view.onDelete = { surfaceId -> latestDelete(surfaceId) }
            view.onEnter = { surfaceId -> latestEnter(surfaceId) }
        },
        modifier = modifier.size(1.dp)
    )
}
