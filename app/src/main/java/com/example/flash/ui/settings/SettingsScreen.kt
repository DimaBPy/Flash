package com.example.flash.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.flash.R
import com.example.flash.ui.theme.ThemeMode
import com.example.flash.ui.theme.ThemeRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeRepository: ThemeRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val language  by themeRepository.language.collectAsStateWithLifecycle("")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        HorizontalDivider()

        // Theme selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            SingleChoiceSegmentedButtonRow {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { scope.launch { themeRepository.setThemeMode(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = {
                            Text(
                                text = when (mode) {
                                    ThemeMode.LIGHT  -> stringResource(R.string.theme_light)
                                    ThemeMode.DARK   -> stringResource(R.string.theme_dark)
                                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // Language selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            val langs = listOf("en", "ru")
            SingleChoiceSegmentedButtonRow {
                langs.forEachIndexed { index, lang ->
                    SegmentedButton(
                        selected = language == lang || (language.isEmpty() && lang == "en"),
                        onClick = {
                            scope.launch { themeRepository.setLanguage(lang) }
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(lang)
                            )
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, langs.size),
                        label = {
                            Text(
                                text = if (lang == "en") stringResource(R.string.lang_en)
                                       else stringResource(R.string.lang_ru),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
