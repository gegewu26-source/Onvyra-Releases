package me.rerere.androidcontrol.shell

import androidx.annotation.Keep

/** Stable DEX entry point used by app_process; preserve this class when shrinking. */
@Keep
object HelperMain {
    @JvmStatic fun main(args: Array<String>) = HelperProcess.main(args)
}
