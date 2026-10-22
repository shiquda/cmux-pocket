package app.cmuxpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import app.cmuxpocket.R

/**
 * First-launch guide: the two commands that install the Gateway on the Mac, then the QR scan
 * that imports its endpoint. It reuses the same scanner and import preview as
 * Settings → Connection; nothing here talks to the network on its own.
 */
@Composable
fun OnboardingScreen(
    onScanQrClick: () -> Unit,
    onSkip: () -> Unit,
) {
    val theme = CmuxTheme.colors
    Surface(modifier = Modifier.fillMaxSize(), color = theme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.onBackground
                )
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    fontSize = 13.sp,
                    color = theme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                OnboardingStep(step = 1, titleRes = R.string.onboarding_step1_title) {
                    Text(
                        text = stringResource(R.string.onboarding_step1_body),
                        fontSize = 12.sp,
                        color = theme.onSurfaceVariant
                    )
                    OnboardingCommand(
                        command = stringResource(R.string.onboarding_install_command),
                        copyLabelRes = R.string.onboarding_copy_install
                    )
                    OnboardingCommand(
                        command = stringResource(R.string.onboarding_setup_command),
                        copyLabelRes = R.string.onboarding_copy_setup
                    )
                }

                OnboardingStep(step = 2, titleRes = R.string.onboarding_step2_title) {
                    Text(
                        text = stringResource(R.string.onboarding_step2_body),
                        fontSize = 12.sp,
                        color = theme.onSurfaceVariant
                    )
                    OnboardingCommand(
                        command = stringResource(R.string.scan_command_qr),
                        copyLabelRes = R.string.onboarding_copy_qr
                    )
                    val hintCommand = stringResource(R.string.onboarding_tailscale_command)
                    val hintText = stringResource(R.string.onboarding_tailscale_hint, hintCommand)
                    Text(
                        text = buildAnnotatedString {
                            append(hintText)
                            val start = hintText.indexOf(hintCommand)
                            if (start >= 0) {
                                addStyle(
                                    SpanStyle(fontFamily = FontFamily.Monospace),
                                    start,
                                    start + hintCommand.length
                                )
                            }
                        },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = theme.onSurfaceVariant
                    )
                }
            }

            // Both actions stay reachable without scrolling; the guide above may overflow.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                Button(
                    onClick = onScanQrClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.primary,
                        contentColor = theme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.onboarding_scan_qr), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.onboarding_skip), fontSize = 13.sp, color = theme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    step: Int,
    titleRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = CmuxTheme.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(theme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = step.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.primary,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(titleRes),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onSurface
                )
            }
            content()
        }
    }
}

/** A command the user must run on the Mac: selectable text plus one-tap copy. */
@Composable
private fun OnboardingCommand(
    command: String,
    copyLabelRes: Int,
) {
    val theme = CmuxTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, theme.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = command,
                    color = theme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(command))
                    Toast.makeText(context, context.getString(R.string.onboarding_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(copyLabelRes),
                    tint = theme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
