package com.hender.fotoanimada

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

/** Consulta las cámaras visibles para Android sin abrir otra sesión. */
internal object CameraDiagnostics {
    fun inspect(context: Context): String {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return "El servicio de cámaras no está disponible"
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.w("Camaratrix", "No se pudo obtener la lista de cámaras Camera2", e)
            return "No se pudo consultar la lista de cámaras"
        }
        if (ids.isEmpty()) return "Android no reportó cámaras disponibles"
        return ids.joinToString("\n") { id -> describeCamera(manager, id) }
    }

    private fun describeCamera(manager: CameraManager, id: String): String {
        val characteristics = try {
            manager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            Log.w("Camaratrix", "No se pudo consultar la cámara $id", e)
            return "Cámara $id · sin confirmar"
        }
        val facing = read(id, "orientación") {
            when (characteristics[CameraCharacteristics.LENS_FACING]) {
                CameraCharacteristics.LENS_FACING_BACK -> "Trasera"
                CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa"
                else -> "Cámara"
            }
        }
        @Suppress("DEPRECATION")
        val extensions = read(id, "extensiones") {
            if (Build.VERSION.SDK_INT < 31) "requiere Android 12"
            else {
                val supported = manager.getCameraExtensionCharacteristics(id).supportedExtensions
                if (supported.isEmpty()) "ninguna" else supported.joinToString(", ") { extension ->
                    when (extension) {
                        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "Auto"
                        // El código de Retoque facial también se usaba en Android 12.
                        CameraExtensionCharacteristics.EXTENSION_BEAUTY -> "Retoque facial"
                        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "Retrato"
                        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "Noche"
                        else -> "tipo $extension"
                    }
                }
            }
        }
        val raw = read(id, "RAW") {
            available(
                characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            )
        }
        val ultraHdr = read(id, "Ultra HDR") {
            if (Build.VERSION.SDK_INT < 34) "requiere Android 14"
            else available(
                characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?.isOutputSupportedFor(ImageFormat.JPEG_R),
            )
        }
        val lowLight = read(id, "Low Light Boost") {
            if (Build.VERSION.SDK_INT < 35) "requiere Android 15"
            else available(
                characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]
                    ?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY),
            )
        }
        val ev = read(id, "exposición") {
            val range = characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
            val step = characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]
            if (range == null || step == null) "sin confirmar"
            else "${range.lower} a ${range.upper} pasos de $step"
        }
        return "$facing $id · Extensiones: $extensions · RAW: $raw · Ultra HDR: $ultraHdr" +
            " · Low Light Boost: $lowLight · EV: $ev"
    }

    private inline fun read(id: String, label: String, query: () -> String): String = try {
        query()
    } catch (e: Exception) {
        Log.w("Camaratrix", "No se pudo consultar $label en cámara $id", e)
        "sin confirmar"
    }

    private fun available(supported: Boolean?): String = when (supported) {
        true -> "sí"
        false -> "no"
        null -> "sin confirmar"
    }
}
