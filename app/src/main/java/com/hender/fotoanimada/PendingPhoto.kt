package com.hender.fotoanimada

/** Una foto solo se publica después de cerrar el archivo completo. */
internal inline fun publishPendingPhoto(
    write: () -> Unit,
    publish: () -> Unit,
    discard: () -> Unit,
) {
    try {
        write()
        publish()
    } catch (failure: Exception) {
        try {
            discard()
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
