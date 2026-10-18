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
                            text = "开源协议与依赖声明",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Open Source Licenses & Notices",
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
                            contentDescription = "Close",
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
                            title = "cmux pocket",
                            license = "GNU Affero General Public License v3.0 (AGPL-3.0)",
                            description = "cmux pocket is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.\n\nThis program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE."
                        )
                    }

                    item {
                        LicenseCard(
                            title = "Jetpack Compose & AndroidX Libraries",
                            license = "Apache License 2.0",
                            description = "Copyright (C) The Android Open Source Project. Licensed under the Apache License, Version 2.0."
                        )
                    }

                    item {
                        LicenseCard(
                            title = "OkHttp & Okio",
                            license = "Apache License 2.0",
                            description = "Copyright 2019 Square, Inc. Licensed under the Apache License, Version 2.0."
                        )
                    }

                    item {
                        LicenseCard(
                            title = "Kotlin & KotlinX (Coroutines, Serialization)",
                            license = "Apache License 2.0",
                            description = "Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors."
                        )
                    }

                    item {
                        LicenseCard(
                            title = "CameraX & ZXing Core",
                            license = "Apache License 2.0",
                            description = "ZXing: Copyright (C) 2008 ZXing authors. Licensed under the Apache License, Version 2.0."
                        )
                    }

                    item {
                        LicenseCard(
                            title = "Maple Mono Font",
                            license = "SIL Open Font License 1.1",
                            description = "Copyright (c) 2021-2024 Subframe & Maple Mono Project Contributors. Licensed under SIL Open Font License, Version 1.1."
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
                    Text("关闭", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
