package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mybrowser.security.SecurityChecker
import com.mybrowser.security.CertificateDetails
import com.mybrowser.security.SecurityLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SecurityIndicator(
    url: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isNativeHomepage = url == "about:blank"
    val securityLevel = SecurityChecker.getSecurityLevel(url)
    val securityInfo = SecurityChecker.getSecurityInfo(url)

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = !isNativeHomepage, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            // about:blank is the backing document for Pure's native navigation home,
            // not a failed or dangerous network connection.
            imageVector = if (isNativeHomepage) {
                Icons.Default.Home
            } else {
                when (securityLevel) {
                    SecurityLevel.SECURE -> Icons.Default.Lock
                    SecurityLevel.WARNING -> Icons.Default.Warning
                    SecurityLevel.INSECURE -> Icons.Default.Warning
                    SecurityLevel.DANGEROUS -> Icons.Default.Close
                }
            },
            contentDescription = if (isNativeHomepage) "导航首页" else securityInfo.protocol,
            tint = if (isNativeHomepage) {
                MaterialTheme.colorScheme.primary
            } else {
                when (securityLevel) {
                    SecurityLevel.SECURE -> Color(0xFF4CAF50)
                    SecurityLevel.WARNING -> Color(0xFFFF9800)
                    SecurityLevel.INSECURE -> Color.Gray
                    SecurityLevel.DANGEROUS -> Color(0xFFF44336)
                }
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityInfoDialog(
    url: String?,
    certificate: CertificateDetails?,
    onDismiss: () -> Unit
) {
    val securityInfo = SecurityChecker.getSecurityInfo(url)
    val securityLevel = SecurityChecker.getSecurityLevel(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = when (securityLevel) {
                    SecurityLevel.SECURE -> Icons.Default.Lock
                    SecurityLevel.WARNING -> Icons.Default.Warning
                    SecurityLevel.INSECURE -> Icons.Default.Warning
                    SecurityLevel.DANGEROUS -> Icons.Default.Close
                },
                contentDescription = null,
                tint = when (securityLevel) {
                    SecurityLevel.SECURE -> Color(0xFF4CAF50)
                    SecurityLevel.WARNING -> Color(0xFFFF9800)
                    SecurityLevel.INSECURE -> Color.Gray
                    SecurityLevel.DANGEROUS -> Color(0xFFF44336)
                },
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = when (securityLevel) {
                    SecurityLevel.SECURE -> "安全连接"
                    SecurityLevel.WARNING -> "连接存在警告"
                    SecurityLevel.INSECURE -> "不安全连接"
                    SecurityLevel.DANGEROUS -> "危险连接"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoRow("网站", url ?: "未知")
                InfoRow("协议", securityInfo.protocol)

                if (certificate != null) {
                    CertificateCard(certificate)
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (securityInfo.protocol == "HTTPS") {
                                    "暂时无法读取证书，请等待页面加载完成后重试。"
                                } else {
                                    "此页面没有 TLS 证书。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (securityInfo.hasWarnings) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = securityInfo.warningMessage ?: "未知警告",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Text(
                    text = when (securityLevel) {
                        SecurityLevel.SECURE -> "连接已经加密。证书信息可帮助确认当前网站的身份。"
                        SecurityLevel.WARNING -> "此连接可能存在安全问题，请谨慎输入敏感信息。"
                        SecurityLevel.INSECURE -> "此连接未加密，请勿输入密码、付款信息等敏感内容。"
                        SecurityLevel.DANGEROUS -> "无法确认此页面的安全性，请立即离开。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CertificateCard(certificate: CertificateDetails) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("证书信息", style = MaterialTheme.typography.titleSmall)
            InfoRow(
                "证书使用者",
                certificate.subjectCommonName
                    ?: certificate.subjectDistinguishedName
                    ?: "未提供",
            )
            certificate.subjectOrganization?.let { InfoRow("使用者组织", it) }
            certificate.subjectOrganizationalUnit?.let { InfoRow("使用者部门", it) }
            InfoRow(
                "签发机构",
                certificate.issuerCommonName
                    ?: certificate.issuerOrganization
                    ?: certificate.issuerDistinguishedName
                    ?: "未提供",
            )
            certificate.issuerOrganization
                ?.takeIf { it != certificate.issuerCommonName }
                ?.let { InfoRow("签发组织", it) }
            certificate.issuerOrganizationalUnit?.let { InfoRow("签发部门", it) }
            certificate.validFromMillis?.let { InfoRow("有效期开始", formatCertificateDate(it)) }
            certificate.validUntilMillis?.let { InfoRow("有效期结束", formatCertificateDate(it)) }
            certificate.isCurrentlyValid?.let { valid ->
                Text(
                    text = if (valid) "证书当前在有效期内" else "证书当前不在有效期内",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (valid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

private fun formatCertificateDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date(epochMillis))

@Composable
fun SSLErrorDialog(
    url: String,
    onProceed: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color(0xFFF44336),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "SSL 证书验证失败",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("网站: $url", fontSize = 13.sp)

                Surface(
                    color = Color(0xFFF44336).copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "这可能意味着：",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• 网站的证书已过期", fontSize = 12.sp)
                        Text("• 网站的证书不受信任", fontSize = 12.sp)
                        Text("• 有人试图窃取您的信息", fontSize = 12.sp)
                    }
                }

                Text(
                    text = "⚠️ 建议：不要继续访问此网站",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("返回安全页面", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onProceed) {
                Text("仍然继续 (不安全)", color = Color(0xFFF44336))
            }
        }
    )
}
