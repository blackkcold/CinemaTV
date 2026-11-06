package dev.cinematv.diagnostics

/**
 * Dedicated subclass follows the repository's provider convention; some vendor devices
 * mis-handle a manifest-only base FileProvider.
 */
class DiagnosticFileProvider : androidx.core.content.FileProvider()
