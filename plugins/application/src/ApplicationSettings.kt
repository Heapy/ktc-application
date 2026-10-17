package io.heapy.ktc.plugins.application

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface ApplicationSettings {
    /** Launcher name under bin/. Defaults to the module name. */
    val applicationName: String?

    /** Top-level directory and archive name. Defaults to the module name. */
    val distributionName: String?

    /** Written to conf/jvm.options, read by Java without shell evaluation. */
    val defaultJvmArgs: List<String> get() = emptyList()
}
