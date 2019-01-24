package net.osmand.telegram.helpers

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.osmand.PlatformUtil
import net.osmand.telegram.TelegramApplication
import org.apache.commons.logging.Log
import java.util.*

class MessagesDbHelper(val app: TelegramApplication) {

	val log: Log = PlatformUtil.getLog(MessagesDbHelper::class.java)

	private val locationMessages = HashSet<LocationMessage>()

	private val sqliteHelper: SQLiteHelper

	init {
		sqliteHelper = SQLiteHelper(app)
		readMessages()
	}

	fun getLocationMessages(): Set<LocationMessage> {
		return this.locationMessages
	}

	fun getPreparedToShareMessages(): List<LocationMessage> {
		val currentUserId = app.telegramHelper.getCurrentUserId()
		val a = this.locationMessages.filter { it.status == 0 && it.userId == currentUserId }
			.sortedBy { it.date }
		return a
	}

	fun saveMessages() {
		clearMessages()
		synchronized(locationMessages) {
			sqliteHelper.addLocationMessages(locationMessages)
		}
	}

	fun readMessages() {
		val messages = sqliteHelper.getLocationMessages()
		synchronized(locationMessages) {
			locationMessages.addAll(messages)
		}
	}

	fun clearMessages() {
		sqliteHelper.clearLocationMessages()
	}

	fun addLocationMessage(locationMessage: LocationMessage) {
		log.debug("addLocationMessage $locationMessage")
		synchronized(locationMessages) {
			locationMessages.add(locationMessage)
		}
	}

	fun updateLocationMessage(new: LocationMessage, current: LocationMessage) {
		log.debug("updateLocationMessage  new - $new")
		log.debug("updateLocationMessage current - $current")
		current.messageId = new.messageId
		current.status = new.status
	}

	private fun removeMessage(locationMessage: LocationMessage) {
		synchronized(locationMessages) {
			locationMessages.remove(locationMessage)
		}
	}

	fun collectRecordedDataForUser(
		userId: Int,
		chatId: Long,
		start: Long,
		end: Long
	): List<MessagesDbHelper.LocationMessage> {
		return if (chatId == 0L) {
			val a = locationMessages.sortedWith(compareBy({ it.userId }, { it.chatId }))
			val b = a.filter { it.userId == userId }
			val c = b.filter { it.date in (start + 1)..(end - 1) }
			val d = c.filter { it.messageId > 0 }
			d
		} else {
			locationMessages.sortedWith(compareBy({ it.userId }, { it.chatId }))
				.filter { it.chatId == chatId && it.userId == userId && it.status > LocationMessage.STATUS_PENDING && it.date in (start + 1)..(end - 1) }
		}
	}

	fun collectRecordedDataForUsers(
		start: Long,
		end: Long,
		ignoredUsersIds: ArrayList<Int>
	): List<MessagesDbHelper.LocationMessage> {
		return locationMessages.sortedWith(compareBy({ it.userId }, { it.chatId })).filter {
			it.date in (start + 1)..(end - 1) && !ignoredUsersIds.contains(
				it.userId
			)
		}
	}

	private class SQLiteHelper(context: Context) :
		SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

		override fun onCreate(db: SQLiteDatabase) {
			db.execSQL(TRACKS_TABLE_CREATE)
			db.execSQL("CREATE INDEX $TRACK_DATE_INDEX ON $TRACK_TABLE_NAME (\"$TRACK_COL_DATE\" DESC);")
		}

		override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
			if (oldVersion < 3) {
				db.execSQL("ALTER TABLE $TRACK_TABLE_NAME ADD $TRACK_COL_TYPE int")
			}
			if (oldVersion < 4) {
				db.execSQL("ALTER TABLE $TRACK_TABLE_NAME ADD $TRACK_COL_MESSAGE_STATUS int")
				db.execSQL("ALTER TABLE $TRACK_TABLE_NAME ADD $TRACK_COL_MESSAGE_ID long")
			}
		}

		internal fun addLocationMessages(locationMessages: Set<LocationMessage>) {
			locationMessages.forEach {
				writableDatabase?.execSQL(
					TRACKS_TABLE_INSERT,
					arrayOf(
						it.userId,
						it.chatId,
						it.lat,
						it.lon,
						it.altitude,
						it.speed,
						it.hdop,
						it.date,
						it.type,
						it.status,
						it.messageId
					)
				)
			}
		}

		internal fun getLocationMessages(): Set<LocationMessage> {
			val res = HashSet<LocationMessage>()
			readableDatabase?.rawQuery(TRACKS_TABLE_SELECT, null)?.apply {
				if (moveToFirst()) {
					do {
						res.add(readLocationMessage(this@apply))
					} while (moveToNext())
				}
				close()
			}
			return res
		}

		internal fun readLocationMessage(cursor: Cursor): LocationMessage {
			val userId = cursor.getInt(0)
			val chatId = cursor.getLong(1)
			val lat = cursor.getDouble(2)
			val lon = cursor.getDouble(3)
			val altitude = cursor.getDouble(4)
			val speed = cursor.getDouble(5)
			val hdop = cursor.getDouble(6)
			val date = cursor.getLong(7)
			val textInfo = cursor.getInt(8)
			val status = cursor.getInt(9)
			val messageId = cursor.getLong(10)
			return LocationMessage(
				userId,
				chatId,
				lat,
				lon,
				altitude,
				speed,
				hdop,
				date,
				textInfo,
				status,
				messageId
			)
		}

		internal fun clearLocationMessages() {
			writableDatabase?.execSQL(TRACKS_TABLE_CLEAR)
		}

		companion object {

			private const val DATABASE_NAME = "tracks"
			private const val DATABASE_VERSION = 4

			private const val TRACK_TABLE_NAME = "track"
			private const val TRACK_COL_USER_ID = "user_id"
			private const val TRACK_COL_CHAT_ID = "chat_id"
			private const val TRACK_COL_DATE = "date"
			private const val TRACK_COL_LAT = "lat"
			private const val TRACK_COL_LON = "lon"
			private const val TRACK_COL_ALTITUDE = "altitude"
			private const val TRACK_COL_SPEED = "speed"
			private const val TRACK_COL_HDOP = "hdop"
			private const val TRACK_COL_TYPE =
				"type" // 0 = user map message, 1 = user text message, 2 = bot map message, 3 = bot text message
			private const val TRACK_COL_MESSAGE_STATUS =
				"status" // 0 = preparing , 1 = pending, 2 = sent, 3 = error
			private const val TRACK_COL_MESSAGE_ID = "message_id"

			private const val TRACK_DATE_INDEX = "date_index"

			private const val TRACKS_TABLE_INSERT =
				("INSERT INTO $TRACK_TABLE_NAME ($TRACK_COL_USER_ID, $TRACK_COL_CHAT_ID, $TRACK_COL_LAT, $TRACK_COL_LON, $TRACK_COL_ALTITUDE, $TRACK_COL_SPEED, $TRACK_COL_HDOP, $TRACK_COL_DATE, $TRACK_COL_TYPE, $TRACK_COL_MESSAGE_STATUS, $TRACK_COL_MESSAGE_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

			private const val TRACKS_TABLE_CREATE =
				("CREATE TABLE IF NOT EXISTS $TRACK_TABLE_NAME ($TRACK_COL_USER_ID long, $TRACK_COL_CHAT_ID long,$TRACK_COL_LAT double, $TRACK_COL_LON double, $TRACK_COL_ALTITUDE double, $TRACK_COL_SPEED float, $TRACK_COL_HDOP double, $TRACK_COL_DATE long, $TRACK_COL_TYPE int, $TRACK_COL_MESSAGE_STATUS int, $TRACK_COL_MESSAGE_ID long )")

			private const val TRACKS_TABLE_SELECT =
				"SELECT $TRACK_COL_USER_ID, $TRACK_COL_CHAT_ID, $TRACK_COL_LAT, $TRACK_COL_LON, $TRACK_COL_ALTITUDE, $TRACK_COL_SPEED, $TRACK_COL_HDOP, $TRACK_COL_DATE, $TRACK_COL_TYPE, $TRACK_COL_MESSAGE_STATUS, $TRACK_COL_MESSAGE_ID FROM $TRACK_TABLE_NAME"

			private const val TRACKS_TABLE_CLEAR = "DELETE FROM $TRACK_TABLE_NAME"
		}
	}

	data class LocationMessage(
		val userId: Int,
		val chatId: Long,
		val lat: Double,
		val lon: Double,
		val altitude: Double,
		val speed: Double,
		val hdop: Double,
		val date: Long,
		val type: Int,
		var status: Int,
		var messageId: Long
	) {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other == null || javaClass != other.javaClass) return false

			val locationMessage = other as LocationMessage

			if (userId != locationMessage.userId) return false
			if (chatId != locationMessage.chatId) return false
			if (lat != locationMessage.lat) return false
			if (lon != locationMessage.lon) return false
			if (altitude != locationMessage.altitude) return false
			if (speed != locationMessage.speed) return false
			if (hdop != locationMessage.hdop) return false
			if (date != locationMessage.date) return false
			if (type != locationMessage.type) return false
			if (status != locationMessage.status) return false

			return messageId != locationMessage.messageId

		}

		override fun hashCode(): Int {
			var result = userId
			result = 31 * result + chatId.hashCode()
			result = 31 * result + lat.hashCode()
			result = 31 * result + lon.hashCode()
			result = 31 * result + altitude.hashCode()
			result = 31 * result + speed.hashCode()
			result = 31 * result + hdop.hashCode()
			result = 31 * result + date.hashCode()
			result = 31 * result + type
			result = 31 * result + status
			result = 31 * result + messageId.hashCode()
			return result
		}

		companion object {

			const val STATUS_PREPARING = 0
			const val STATUS_PENDING = 1
			const val STATUS_SENT = 2
			const val STATUS_ERROR = 3

			const val TYPE_USER_MAP = 0
			const val TYPE_USER_TEXT = 1
			const val TYPE_BOT_MAP = 2
			const val TYPE_BOT_TEXT = 3
		}
	}
}