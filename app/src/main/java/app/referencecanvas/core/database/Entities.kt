package app.referencecanvas.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("parentFolderId")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val parentFolderId: String? = null,
)

@Entity(
    tableName = "boards",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId"), Index("isFavorite"), Index("updatedAt")],
)
data class BoardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val description: String = "",
    val coverReferenceId: String? = null,
    val viewMode: String = "Canvas",
    // Double: canvas coordinates must not lose precision far from the origin.
    val canvasOffsetX: Double = 0.0,
    val canvasOffsetY: Double = 0.0,
    val canvasZoom: Float = 1f,
    val sortMode: String = "Manual",
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val folderId: String? = null,
    val accent: String? = null,
)

/**
 * A reference's own record. File bytes live on disk (managed storage); only metadata is stored here.
 * [deletedAt] non-null means the reference is in the trash.
 */
@Entity(
    tableName = "reference_items",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("folderId"),
        Index("mimeType"),
        Index("title"),
        Index("createdAt"),
        Index("isFavorite"),
        Index("deletedAt"),
    ],
)
data class ReferenceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalFileName: String,
    val mimeType: String,
    val createdAt: Long,
    val updatedAt: Long,
    val description: String = "",
    /** "MANAGED" (copied into app storage), "EXTERNAL" (persisted URI) or "LINK" (URL metadata only). */
    val storageMode: String = "MANAGED",
    val sourceUri: String? = null,
    val localRelativePath: String? = null,
    val fileSizeBytes: Long = 0,
    val lastOpenedAt: Long? = null,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMs: Long? = null,
    val checksum: String? = null,
    val folderId: String? = null,
    val deletedAt: Long? = null,
)

/** Board membership. One reference can sit on many boards without duplicating its file. */
@Entity(
    tableName = "board_references",
    primaryKeys = ["boardId", "referenceId"],
    foreignKeys = [
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["referenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("referenceId")],
)
data class BoardReferenceEntity(
    val boardId: String,
    val referenceId: String,
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val zIndex: Int = 0,
    val width: Float = 240f,
    val height: Float = 240f,
    val sortOrder: Long = 0,
    val label: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int? = null,
)

@Entity(
    tableName = "reference_tags",
    primaryKeys = ["referenceId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["referenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class ReferenceTagEntity(
    val referenceId: String,
    val tagId: String,
)

/** Search terms the user submitted, newest first; bounded and clearable. */
@Entity(tableName = "recent_searches", indices = [Index("usedAt")])
data class RecentSearchEntity(
    @PrimaryKey val term: String,
    val usedAt: Long,
)

/** Editable annotation data, stored separately from the original file. */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = ReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["referenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("referenceId")],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val referenceId: String,
    val type: String,
    val serializedData: String,
    val createdAt: Long,
    val updatedAt: Long,
)
