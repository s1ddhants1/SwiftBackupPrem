package io.github.s1ddhants1.swiftbackupprem.domain.usecase

import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

class DetectCandidateUidsUseCase {
    operator fun invoke(
        context: Context,
        classLoader: ClassLoader? = null,
        prefs: PreferencesManager? = null
    ): List<String> {
        val cl = classLoader ?: javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val candidateUids = BackupCrypto.resolveCandidateUids(context, cl, prefs = prefs)
        val mergedUids = BackupCrypto.syncDetectedUids(context, candidateUids)
        return if (mergedUids.isNotEmpty()) mergedUids else candidateUids
    }
}
