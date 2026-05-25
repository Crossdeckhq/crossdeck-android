// Root build file. Plugin versions live here; the `:crossdeck`
// module applies them. Keep this file thin — adding configuration
// here makes every sub-module inherit it implicitly, which is the
// wrong default for a public library project.

plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
