package dev.typester.evencompanion.core

import dev.typester.evencompanion.core.uniffi.Core

object EvenCore {
    val instance: Core by lazy { Core() }
    val DEFAULT_PORT: UShort = 44423.toUShort()
}
