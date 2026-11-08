package com.axiel7.anihyou.core.localization

/** User-actionable lifecycle failures; technical details stay in the cause. */
enum class CatalogFailureCode {
    FILE_TOO_LARGE,
    INVALID_UTF8,
    INVALID_FORMAT,
    EMPTY_CATALOG,
    INVALID_VERSION,
    DOWNGRADE,
    VERSION_COLLISION,
    METADATA_MISMATCH,
    STALE_OPERATION,
    STORAGE_FAILURE,
    IMPORT_FAILURE,
    NETWORK_FAILURE,
    INVALID_MANIFEST,
    INTERNAL_ERROR,
}

class CatalogFailureException(
    val code: CatalogFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)
