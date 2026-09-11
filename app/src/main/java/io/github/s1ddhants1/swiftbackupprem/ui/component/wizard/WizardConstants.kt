package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.annotation.StringRes
import io.github.s1ddhants1.swiftbackupprem.R

const val TOTAL_WIZARD_STEPS = 6

const val GOOGLE_DRIVE_OAUTH_SCOPE = "https://www.googleapis.com/auth/drive.file"

const val FIREBASE_DATABASE_RULES = "{\n" +
        "  \"rules\": {\n" +
        "    \"users\": {\n" +
        "      \"\$uid\": {\n" +
        "        \".read\": \"\$uid === auth.uid\",\n" +
        "        \".write\": \"\$uid === auth.uid\"\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}"

@StringRes
fun getWizardStepTitleRes(step: Int): Int = when (step) {
    1 -> R.string.wizard_title_step_1
    2 -> R.string.wizard_title_step_2
    3 -> R.string.wizard_title_step_3
    4 -> R.string.wizard_title_step_4
    5 -> R.string.wizard_title_step_5
    6 -> R.string.wizard_title_step_6
    else -> R.string.screen_settings
}
