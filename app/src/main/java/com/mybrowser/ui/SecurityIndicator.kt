package com.mybrowser.ui

import com.mybrowser.R
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
    val textResources = localizedResources()
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
            contentDescription = if (isNativeHomepage) textResources.getString(R.string.ui_shortcuts_homepage) else securityInfo.protocol,
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
    val textResources = localizedResources()
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
                    SecurityLevel.SECURE -> textResources.getString(R.string.ui_secure_connection)
                    SecurityLevel.WARNING -> textResources.getString(R.string.ui_connection_warning)
                    SecurityLevel.INSECURE -> textResources.getString(R.string.ui_insecure_connection)
                    SecurityLevel.DANGEROUS -> textResources.getString(R.string.ui_dangerous_connection)
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoRow(textResources.getString(R.string.ui_website), url ?: textResources.getString(R.string.ui_unknown))
                InfoRow(textResources.getString(R.string.ui_protocol), securityInfo.protocol)

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
                                    textResources.getString(R.string.ui_the_certificate_is_not_available_yet_wait_for)
                                } else {
                                    textResources.getString(R.string.ui_this_page_has_no_tls_certificate)
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
                                text = securityInfo.warningRes?.let { textResources.getString(it, *securityInfo.warningArgs.toTypedArray()) } ?: textResources.getString(R.string.ui_unknown_warning),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Text(
                    text = when (securityLevel) {
                        SecurityLevel.SECURE -> textResources.getString(R.string.ui_the_connection_is_encrypted_certificate_details_help_identify)
                        SecurityLevel.WARNING -> textResources.getString(R.string.ui_this_connection_may_have_security_issues_be_careful)
                        SecurityLevel.INSECURE -> textResources.getString(R.string.ui_this_connection_is_not_encrypted_do_not_enter)
                        SecurityLevel.DANGEROUS -> textResources.getString(R.string.ui_the_safety_of_this_page_cannot_be_verified)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(textResources.getString(R.string.ui_ok))
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
    val textResources = localizedResources()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(textResources.getString(R.string.ui_certificate_details), style = MaterialTheme.typography.titleSmall)
            InfoRow(
                textResources.getString(R.string.ui_subject),
                certificate.subjectCommonName
                    ?: certificate.subjectDistinguishedName
                    ?: textResources.getString(R.string.ui_not_provided),
            )
            certificate.subjectOrganization?.let { InfoRow(textResources.getString(R.string.ui_subject_organization), it) }
            certificate.subjectOrganizationalUnit?.let { InfoRow(textResources.getString(R.string.ui_subject_unit), it) }
            InfoRow(
                textResources.getString(R.string.ui_issuer),
                certificate.issuerCommonName
                    ?: certificate.issuerOrganization
                    ?: certificate.issuerDistinguishedName
                    ?: textResources.getString(R.string.ui_not_provided),
            )
            certificate.issuerOrganization
                ?.takeIf { it != certificate.issuerCommonName }
                ?.let { InfoRow(textResources.getString(R.string.ui_issuer_organization), it) }
            certificate.issuerOrganizationalUnit?.let { InfoRow(textResources.getString(R.string.ui_issuer_unit), it) }
            certificate.validFromMillis?.let { InfoRow(textResources.getString(R.string.ui_valid_from), formatCertificateDate(it)) }
            certificate.validUntilMillis?.let { InfoRow(textResources.getString(R.string.ui_valid_until), formatCertificateDate(it)) }
            certificate.isCurrentlyValid?.let { valid ->
                Text(
                    text = if (valid) textResources.getString(R.string.ui_the_certificate_is_currently_valid) else textResources.getString(R.string.ui_the_certificate_is_outside_its_validity_period),
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
    val textResources = localizedResources()
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
                text = textResources.getString(R.string.ui_ssl_certificate_verification_failed),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(textResources.getString(R.string.ui_website_b7aba5, url), fontSize = 13.sp)

                Surface(
                    color = Color(0xFFF44336).copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = textResources.getString(R.string.ui_possible_reasons),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(textResources.getString(R.string.ui_the_website_certificate_has_expired), fontSize = 12.sp)
                        Text(textResources.getString(R.string.ui_the_website_certificate_is_not_trusted), fontSize = 12.sp)
                        Text(textResources.getString(R.string.ui_someone_may_be_trying_to_steal_your_information), fontSize = 12.sp)
                    }
                }

                Text(
                    text = textResources.getString(R.string.ui_we_recommend_leaving_this_website),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(textResources.getString(R.string.ui_go_back_to_safety), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onProceed) {
                Text(textResources.getString(R.string.ui_continue_anyway_unsafe), color = Color(0xFFF44336))
            }
        }
    )
}
