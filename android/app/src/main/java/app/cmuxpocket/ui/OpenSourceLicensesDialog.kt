package app.cmuxpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.R

@Composable
fun OpenSourceLicensesDialog(
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xFF1C1C1E),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.lic_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.lic_subtitle),
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0x1FFFFFFF))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.lic_close_button),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_cmux_pocket_title),
                            license = stringResource(R.string.lic_card_cmux_pocket_license),
                            description = stringResource(R.string.lic_card_cmux_pocket_desc)
                        )
                    }

                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_compose_title),
                            license = stringResource(R.string.lic_card_compose_license),
                            description = stringResource(R.string.lic_card_compose_desc)
                        )
                    }

                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_okhttp_title),
                            license = stringResource(R.string.lic_card_okhttp_license),
                            description = stringResource(R.string.lic_card_okhttp_desc)
                        )
                    }

                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_kotlin_title),
                            license = stringResource(R.string.lic_card_kotlin_license),
                            description = stringResource(R.string.lic_card_kotlin_desc)
                        )
                    }

                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_camerax_title),
                            license = stringResource(R.string.lic_card_camerax_license),
                            description = stringResource(R.string.lic_card_camerax_desc)
                        )
                    }

                    item {
                        LicenseCard(
                            title = stringResource(R.string.lic_card_maple_title),
                            license = stringResource(R.string.lic_card_maple_license),
                            description = stringResource(R.string.lic_card_maple_desc)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A84FF),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.lic_dismiss), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LicenseCard(
    title: String,
    license: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2C2C2E))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = license,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF30D158),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        Text(
            text = description,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFFE5E5EA),
            fontFamily = FontFamily.Monospace
        )
    }
}
