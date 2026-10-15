package com.stockpile.volume.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A stockpile, stored on the phone. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val densityTM3: Double? = null,
    val createdAt: Long,

    // ---- On-phone (ARCore) measurement
    val measurement: MeasurementRecord? = null,
    /** SHA-256 of record.json exactly as stored – this is what goes on the blockchain. */
    val recordSha256: String? = null,
    val anchor: ChainAnchor? = null,

    // ---- Optional photo capture for the (later) processing server
    /** photo file name -> compass heading (degrees) the camera faced when it was taken */
    val photoHeadings: Map<String, Float> = emptyMap(),
    val nextPhotoIndex: Int = 1,
    /** photos already on the server (lets interrupted uploads resume) */
    val uploaded: Set<String> = emptySet(),
    val remoteJobId: String? = null,
    val remote: RemoteJob? = null,
) {
    val photoCount: Int get() = photoHeadings.size

    /** Number of 10° compass sectors that have at least one photo (0..36). */
    val coveredSectors: Int get() = photoHeadings.values.map { sectorOf(it) }.toSet().size

    /** True when the anchored hash still matches the current measurement. */
    val anchorMatches: Boolean get() = anchor != null && anchor.recordSha256 == recordSha256

    companion object {
        const val SECTORS = 36
        fun sectorOf(heading: Float): Int = (((heading % 360f) + 360f) % 360f / 10f).toInt().coerceIn(0, SECTORS - 1)
    }
}

@Serializable
data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Double? = null)

@Serializable
data class HeightmapRef(val cols: Int, val rows: Int, val cellSizeM: Double, val file: String, val sha256: String)

/**
 * The measurement record. Saved as record.json; its SHA-256 is what gets written to the XRP Ledger,
 * so anyone holding the file can prove it was not changed after anchoring.
 * Field order is fixed – don't reorder fields, it changes the hash format.
 */
@Serializable
data class MeasurementRecord(
    val schema: String = "stockpile-volume/1",
    val id: String,
    val project: String,
    val measuredAt: String,
    val method: String,
    val device: String,
    val app: String,
    val location: GeoPoint? = null,
    val volumeM3: Double,
    val fillM3: Double,
    val cutM3: Double,
    val footprintM2: Double,
    val boundaryAreaM2: Double,
    val maxHeightM: Double,
    val baseSlopePct: Double,
    val baseRmseM: Double,
    val coveragePct: Double,
    val cellSizeM: Double,
    val pathLengthM: Double,
    val densityTM3: Double? = null,
    val tonnes: Double? = null,
    val voxels: Int,
    val heightmap: HeightmapRef,
    val warnings: List<String> = emptyList(),
    /** Manual measurement only: the dimensions the user entered (metres). */
    val inputs: Map<String, Double>? = null,
)

/** Where the record hash was anchored on the XRP Ledger. */
@Serializable
data class ChainAnchor(
    val network: String,                 // MAINNET / TESTNET
    val recordSha256: String,
    val payloadUuid: String,
    val signUrl: String? = null,
    val status: String = PENDING,
    val txid: String? = null,
    val account: String? = null,
    val dispatchedResult: String? = null,
    val ledgerIndex: Long? = null,
    val validatedAt: String? = null,
    val error: String? = null,
) {
    val isFinal: Boolean get() = status in setOf(VALIDATED, REJECTED, EXPIRED, FAILED)

    companion object {
        const val PENDING = "pending"        // waiting for the user in Xaman
        const val SIGNED = "signed"          // signed + submitted, waiting for ledger validation
        const val VALIDATED = "validated"    // in a validated ledger, memo checked
        const val REJECTED = "rejected"      // user declined in Xaman
        const val EXPIRED = "expired"
        const val FAILED = "failed"
    }
}

/** Job state as reported by the processing server (optional mode). */
@Serializable
data class RemoteJob(
    val id: String,
    val name: String = "",
    val status: String,
    val progress: Int = 0,
    val message: String? = null,
    @SerialName("image_count") val imageCount: Int = 0,
    val result: VolumeResult? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = status in setOf(QUEUED, SUBMITTING, PROCESSING, COMPUTING)

    companion object {
        const val CREATED = "created"
        const val QUEUED = "queued"
        const val SUBMITTING = "submitting"
        const val PROCESSING = "processing"
        const val COMPUTING = "computing"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

@Serializable
data class VolumeResult(
    @SerialName("volume_m3") val volumeM3: Double,
    @SerialName("fill_m3") val fillM3: Double = 0.0,
    @SerialName("cut_m3") val cutM3: Double = 0.0,
    @SerialName("footprint_m2") val footprintM2: Double = 0.0,
    @SerialName("boundary_area_m2") val boundaryAreaM2: Double = 0.0,
    @SerialName("max_height_m") val maxHeightM: Double = 0.0,
    @SerialName("base_slope_pct") val baseSlopePct: Double = 0.0,
    @SerialName("base_rmse_m") val baseRmseM: Double = 0.0,
    @SerialName("coverage_pct") val coveragePct: Double = 0.0,
    @SerialName("camera_count") val cameraCount: Int = 0,
    @SerialName("camera_source") val cameraSource: String? = null,
    val tonnes: Double? = null,
    @SerialName("density_t_m3") val densityTM3: Double? = null,
    val warnings: List<String>? = null,
)
