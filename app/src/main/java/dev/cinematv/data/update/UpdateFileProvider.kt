package dev.cinematv.data.update

/** Dedicated subclass avoids vendor compatibility issues with manifest-only base FileProvider. */
class UpdateFileProvider : androidx.core.content.FileProvider()
