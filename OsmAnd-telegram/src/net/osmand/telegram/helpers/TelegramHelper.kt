package net.osmand.telegram.helpers

import android.text.TextUtils
import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.telegram.SHARE_TYPE_MAP
import net.osmand.telegram.SHARE_TYPE_MAP_AND_TEXT
import net.osmand.telegram.SHARE_TYPE_TEXT
import net.osmand.telegram.TelegramSettings
import net.osmand.telegram.helpers.TelegramHelper.TelegramAuthenticationParameterType.*
import net.osmand.telegram.utils.BASE_SHARING_URL
import net.osmand.telegram.utils.GRAYSCALE_PHOTOS_DIR
import net.osmand.telegram.utils.GRAYSCALE_PHOTOS_EXT
import net.osmand.telegram.utils.OsmandLocationUtils
import net.osmand.telegram.utils.OsmandLocationUtils.getLastUpdatedTime
import net.osmand.telegram.utils.OsmandLocationUtils.parseOsmAndBotLocation
import net.osmand.telegram.utils.OsmandLocationUtils.parseTextLocation
import net.osmand.telegram.utils.OsmandLocationUtils.parseOsmAndBotLocationContent
import net.osmand.telegram.utils.OsmandLocationUtils.MessageOsmAndBotLocation
import net.osmand.telegram.utils.OsmandLocationUtils.MessageUserTextLocation
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.Client.ResultHandler
import org.drinkless.td.libcore.telegram.TdApi
import org.drinkless.td.libcore.telegram.TdApi.AuthorizationState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.collections.HashSet


class TelegramHelper private constructor() {

	companion object {
		const val OSMAND_BOT_USERNAME = "osmand_bot"

		private val log = PlatformUtil.getLog(TelegramHelper::class.java)
		private const val CHATS_LIMIT = 100
		private const val IGNORED_ERROR_CODE = 406
		private const val MESSAGE_CANNOT_BE_EDITED_ERROR_CODE = 5

		private const val DEVICE_PREFIX = "Device: "
		private const val LOCATION_PREFIX = "Location: "
		private const val UPDATED_PREFIX = "Updated: "
		private const val USER_TEXT_LOCATION_TITLE = "\uD83D\uDDFA OsmAnd sharing:"

		private const val SHARING_LINK = "https://play.google.com/store/apps/details?id=net.osmand.telegram"

		private const val ALTITUDE_PREFIX = "Altitude: "
		private const val SPEED_PREFIX = "Speed: "
		private const val HDOP_PREFIX = "Horizontal precision: "

		private val UTC_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}

		private val UTC_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		// min and max values for the Telegram API
		const val MIN_LOCATION_MESSAGE_LIVE_PERIOD_SEC = 61
		const val MAX_LOCATION_MESSAGE_LIVE_PERIOD_SEC = 60 * 60 * 24 - 1 // one day

		const val MAX_LOCATION_MESSAGE_HISTORY_SCAN_SEC = 60 * 60 * 24 // one day

		const val SEND_NEW_MESSAGE_INTERVAL_SEC = 10 * 60 // 10 minutes

		private var helper: TelegramHelper? = null

		val instance: TelegramHelper
			get() {
				if (helper == null) {
					helper = TelegramHelper()
				}
				return helper!!
			}
	}

	var messageActiveTimeSec: Long = 0

	var lastTelegramUpdateTime: Int = 0

	private val users = ConcurrentHashMap<Int, TdApi.User>()
	private val contacts = ConcurrentHashMap<Int, TdApi.User>()
	private val basicGroups = ConcurrentHashMap<Int, TdApi.BasicGroup>()
	private val supergroups = ConcurrentHashMap<Int, TdApi.Supergroup>()
	private val secretChats = ConcurrentHashMap<Int, TdApi.SecretChat>()

	private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
	private val chatList = TreeSet<OrderedChat>()

	private val downloadChatFilesMap = ConcurrentHashMap<String, TdApi.Chat>()
	private val downloadUserFilesMap = ConcurrentHashMap<String, TdApi.User>()

	// value.content can be TdApi.MessageLocation or MessageOsmAndBotLocation
	private val usersLocationMessages = ConcurrentHashMap<Long, TdApi.Message>()

	private val usersFullInfo = ConcurrentHashMap<Int, TdApi.UserFullInfo>()
	private val basicGroupsFullInfo = ConcurrentHashMap<Int, TdApi.BasicGroupFullInfo>()
	private val supergroupsFullInfo = ConcurrentHashMap<Int, TdApi.SupergroupFullInfo>()

	var appDir: String? = null
	private var libraryLoaded = false
	private var telegramAuthorizationRequestHandler: TelegramAuthorizationRequestHandler? = null

	private var client: Client? = null
	private var currentUser: TdApi.User? = null
	private var osmandBot: TdApi.User? = null

	private var haveFullChatList: Boolean = false
	private var needRefreshActiveLiveLocationMessages: Boolean = true
	private var requestingActiveLiveLocationMessages: Boolean = false

	private var authorizationState: AuthorizationState? = null
	private var haveAuthorization = false

	private val defaultHandler = DefaultHandler()

	private var updateLiveMessagesExecutor: ScheduledExecutorService? = null

	var textIndex:Long = 0

	var handleTextIndex:Long = 0

	var mapIndex:Long = 0

	var handleMapIndex:Long = 0

	var listener: TelegramListener? = null
	private val incomingMessagesListeners = HashSet<TelegramIncomingMessagesListener>()
	private val outgoingMessagesListeners = HashSet<TelegramOutgoingMessagesListener>()
	private val fullInfoUpdatesListeners = HashSet<FullInfoUpdatesListener>()

	fun addIncomingMessagesListener(listener: TelegramIncomingMessagesListener) {
		incomingMessagesListeners.add(listener)
	}

	fun removeIncomingMessagesListener(listener: TelegramIncomingMessagesListener) {
		incomingMessagesListeners.remove(listener)
	}

	fun addOutgoingMessagesListener(listener: TelegramOutgoingMessagesListener) {
		outgoingMessagesListeners.add(listener)
	}

	fun removeOutgoingMessagesListener(listener: TelegramOutgoingMessagesListener) {
		outgoingMessagesListeners.remove(listener)
	}

	fun addFullInfoUpdatesListener(listener: FullInfoUpdatesListener) {
		fullInfoUpdatesListeners.add(listener)
	}

	fun removeFullInfoUpdatesListener(listener: FullInfoUpdatesListener) {
		fullInfoUpdatesListeners.remove(listener)
	}

	fun getChatList(): TreeSet<OrderedChat> {
		synchronized(chatList) {
			return TreeSet(chatList.filter { !it.isChannel })
		}
	}

	fun getChatListIds() = getChatList().map { it.chatId }

	fun getContacts() = contacts

	fun getChatIds() = chats.keys().toList()

	fun getChat(id: Long) = chats[id]

	fun getUser(id: Int) = users[id]

	fun getOsmandBot() = osmandBot

	fun getCurrentUser() = currentUser

	fun getCurrentUserId() = currentUser?.id ?: -1

	fun getUserMessage(user: TdApi.User) =
		usersLocationMessages.values.firstOrNull { it.senderUserId == user.id }

	fun getChatMessages(chatId: Long) =
		usersLocationMessages.values.filter { it.chatId == chatId }

	fun getMessages() = usersLocationMessages.values.toList()

	fun getMessagesByChatIds(messageExpTime: Long): Map<Long, List<TdApi.Message>> {
		val res = mutableMapOf<Long, MutableList<TdApi.Message>>()
		for (message in usersLocationMessages.values) {
			if (System.currentTimeMillis() / 1000 - getLastUpdatedTime(message) < messageExpTime) {
				var messages = res[message.chatId]
				if (messages != null) {
					messages.add(message)
				} else {
					messages = mutableListOf(message)
					res[message.chatId] = messages
				}
			}
		}
		return res
	}

	fun getBasicGroupFullInfo(id: Int): TdApi.BasicGroupFullInfo? {
		val res = basicGroupsFullInfo[id]
		if (res == null) {
			requestBasicGroupFullInfo(id)
		}
		return res
	}

	fun getSupergroupFullInfo(id: Int): TdApi.SupergroupFullInfo? {
		val res = supergroupsFullInfo[id]
		if (res == null) {
			requestSupergroupFullInfo(id)
		}
		return res
	}

	fun isGroup(chat: TdApi.Chat): Boolean {
		return chat.type is TdApi.ChatTypeSupergroup || chat.type is TdApi.ChatTypeBasicGroup
	}

	fun isPrivateChat(chat: TdApi.Chat): Boolean = chat.type is TdApi.ChatTypePrivate

	fun isSecretChat(chat: TdApi.Chat): Boolean = chat.type is TdApi.ChatTypeSecret

	private fun isChannel(chat: TdApi.Chat): Boolean {
		return chat.type is TdApi.ChatTypeSupergroup && (chat.type as TdApi.ChatTypeSupergroup).isChannel
	}

	enum class TelegramAuthenticationParameterType {
		PHONE_NUMBER,
		CODE,
		PASSWORD
	}

	enum class TelegramAuthorizationState {
		UNKNOWN,
		WAIT_PARAMETERS,
		WAIT_PHONE_NUMBER,
		WAIT_CODE,
		WAIT_PASSWORD,
		READY,
		LOGGING_OUT,
		CLOSING,
		CLOSED
	}

	interface TelegramListener {
		fun onTelegramStatusChanged(prevTelegramAuthorizationState: TelegramAuthorizationState,
									newTelegramAuthorizationState: TelegramAuthorizationState)

		fun onTelegramChatsRead()
		fun onTelegramChatsChanged()
		fun onTelegramChatChanged(chat: TdApi.Chat)
		fun onTelegramChatCreated(chat: TdApi.Chat)
		fun onTelegramUserChanged(user: TdApi.User)
		fun onTelegramError(code: Int, message: String)
	}

	interface TelegramIncomingMessagesListener {
		fun onReceiveChatLocationMessages(chatId: Long, vararg messages: TdApi.Message)
		fun onDeleteChatLocationMessages(chatId: Long, messages: List<TdApi.Message>)
		fun updateLocationMessages()
	}

	interface TelegramOutgoingMessagesListener {
		fun onUpdateMessages(messages: List<TdApi.Message>)
		fun onDeleteMessages(chatId: Long, messages: List<Long>)
		fun onSendLiveLocationError(code: Int, message: String)
	}

	interface FullInfoUpdatesListener {
		fun onBasicGroupFullInfoUpdated(groupId: Int, info: TdApi.BasicGroupFullInfo)
		fun onSupergroupFullInfoUpdated(groupId: Int, info: TdApi.SupergroupFullInfo)
	}

	interface TelegramAuthorizationRequestListener {
		fun onRequestTelegramAuthenticationParameter(parameterType: TelegramAuthenticationParameterType)
		fun onTelegramAuthorizationRequestError(code: Int, message: String)
	}

	inner class TelegramAuthorizationRequestHandler(val telegramAuthorizationRequestListener: TelegramAuthorizationRequestListener) {

		fun applyAuthenticationParameter(parameterType: TelegramAuthenticationParameterType, parameterValue: String) {
			if (!TextUtils.isEmpty(parameterValue)) {
				when (parameterType) {
					PHONE_NUMBER -> client!!.send(TdApi.SetAuthenticationPhoneNumber(parameterValue, false, false), AuthorizationRequestHandler())
					CODE -> client!!.send(TdApi.CheckAuthenticationCode(parameterValue, "", ""), AuthorizationRequestHandler())
					PASSWORD -> client!!.send(TdApi.CheckAuthenticationPassword(parameterValue), AuthorizationRequestHandler())
				}
			}
		}
	}

	fun getTelegramAuthorizationState(): TelegramAuthorizationState {
		val authorizationState = this.authorizationState
				?: return TelegramAuthorizationState.UNKNOWN
		return when (authorizationState.constructor) {
			TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> TelegramAuthorizationState.WAIT_PARAMETERS
			TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> TelegramAuthorizationState.WAIT_PHONE_NUMBER
			TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> TelegramAuthorizationState.WAIT_CODE
			TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> TelegramAuthorizationState.WAIT_PASSWORD
			TdApi.AuthorizationStateReady.CONSTRUCTOR -> TelegramAuthorizationState.READY
			TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> TelegramAuthorizationState.LOGGING_OUT
			TdApi.AuthorizationStateClosing.CONSTRUCTOR -> TelegramAuthorizationState.CLOSING
			TdApi.AuthorizationStateClosed.CONSTRUCTOR -> TelegramAuthorizationState.CLOSED
			else -> TelegramAuthorizationState.UNKNOWN
		}
	}

	fun setTelegramAuthorizationRequestHandler(telegramAuthorizationRequestListener: TelegramAuthorizationRequestListener): TelegramAuthorizationRequestHandler {
		val handler = TelegramAuthorizationRequestHandler(telegramAuthorizationRequestListener)
		this.telegramAuthorizationRequestHandler = handler
		return handler
	}

	init {
		try {
			log.debug("Loading native tdlib...")
			System.loadLibrary("tdjni")
			Client.setLogVerbosityLevel(0)
			libraryLoaded = true
		} catch (e: Throwable) {
			log.error("Failed to load tdlib", e)
		}
	}

	fun init(): Boolean {
		return if (libraryLoaded) {
			// create client
			client = Client.create(UpdatesHandler(), null, null)
			true
		} else {
			false
		}
	}

	fun requestAuthorizationState() {
		client?.send(TdApi.GetAuthorizationState()) { obj ->
			if (obj is TdApi.AuthorizationState) {
				onAuthorizationStateUpdated(obj, true)
			}
		}
	}

	fun networkChange() {
		client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi())) { obj ->
			log.debug(obj)
		}
	}

	fun isInit() = client != null && haveAuthorization

	fun getUserPhotoPath(user: TdApi.User?) = when {
		user == null -> null
		hasLocalUserPhoto(user) -> user.profilePhoto?.small?.local?.path
		else -> {
			if (hasRemoteUserPhoto(user)) {
				requestUserPhoto(user)
			}
			null
		}
	}

	fun getUserGreyPhotoPath(user: TdApi.User?) = when {
		user == null -> null
		hasGrayscaleUserPhoto(user.id) -> "$appDir/$GRAYSCALE_PHOTOS_DIR${user.id}$GRAYSCALE_PHOTOS_EXT"
		else -> null
	}

	fun getOsmAndBotDeviceName(message: TdApi.Message): String {
		var deviceName = ""
		if (message.replyMarkup is TdApi.ReplyMarkupInlineKeyboard) {
			val replyMarkup = message.replyMarkup as TdApi.ReplyMarkupInlineKeyboard
			try {
				deviceName = replyMarkup.rows[0][1].text.split("\\s".toRegex())[1]
			} catch (e: Exception) {

			}
		}
		return deviceName
	}

	fun getUserIdFromChatType(type: TdApi.ChatType) = when (type) {
		is TdApi.ChatTypePrivate -> type.userId
		is TdApi.ChatTypeSecret -> type.userId
		else -> 0
	}

	fun isOsmAndBot(userId: Int) = users[userId]?.username == OSMAND_BOT_USERNAME

	fun isBot(userId: Int) = users[userId]?.type is TdApi.UserTypeBot

	fun getSenderMessageId(message: TdApi.Message): Int {
		val forwardInfo = message.forwardInfo
		return if (forwardInfo != null && forwardInfo is TdApi.MessageForwardedFromUser) {
			forwardInfo.senderUserId
		} else {
			message.senderUserId
		}
	}

	fun startLiveMessagesUpdates(interval: Long) {
		stopLiveMessagesUpdates()

		val updateLiveMessagesExecutor = Executors.newSingleThreadScheduledExecutor()
		this.updateLiveMessagesExecutor = updateLiveMessagesExecutor
		updateLiveMessagesExecutor.scheduleWithFixedDelay({
			incomingMessagesListeners.forEach { it.updateLocationMessages() }
		}, interval, interval, TimeUnit.SECONDS)
	}

	fun stopLiveMessagesUpdates() {
		updateLiveMessagesExecutor?.shutdown()
		updateLiveMessagesExecutor?.awaitTermination(1, TimeUnit.MINUTES)
	}

	fun hasGrayscaleUserPhoto(userId: Int): Boolean {
		return File("$appDir/$GRAYSCALE_PHOTOS_DIR$userId$GRAYSCALE_PHOTOS_EXT").exists()
	}

	private fun isUserLocationMessage(message: TdApi.Message): Boolean {
		val cont = message.content
		return (cont is MessageUserTextLocation || cont is TdApi.MessageLocation)
	}

	private fun hasLocalUserPhoto(user: TdApi.User): Boolean {
		val localPhoto = user.profilePhoto?.small?.local
		return if (localPhoto != null) {
			localPhoto.canBeDownloaded && localPhoto.isDownloadingCompleted && localPhoto.path.isNotEmpty()
		} else {
			false
		}
	}

	private fun hasRemoteUserPhoto(user: TdApi.User): Boolean {
		val remotePhoto = user.profilePhoto?.small?.remote
		return remotePhoto?.id?.isNotEmpty() ?: false
	}

	private fun requestUserPhoto(user: TdApi.User) {
		val remotePhoto = user.profilePhoto?.small?.remote
		if (remotePhoto != null && remotePhoto.id.isNotEmpty()) {
			downloadUserFilesMap[remotePhoto.id] = user
			client!!.send(TdApi.GetRemoteFile(remotePhoto.id, null)) { obj ->
				when (obj.constructor) {
					TdApi.Error.CONSTRUCTOR -> {
						val error = obj as TdApi.Error
						val code = error.code
						if (code != IGNORED_ERROR_CODE) {
							listener?.onTelegramError(code, error.message)
						}
					}
					TdApi.File.CONSTRUCTOR -> {
						val file = obj as TdApi.File
						client!!.send(TdApi.DownloadFile(file.id, 10), defaultHandler)
					}
					else -> listener?.onTelegramError(-1, "Receive wrong response from TDLib: $obj")
				}
			}
		}
	}

	private fun requestChats(reload: Boolean = false, onComplete: (() -> Unit)?) {
		synchronized(chatList) {
			if (reload) {
				chatList.clear()
				haveFullChatList = false
			}
			if (!haveFullChatList && CHATS_LIMIT > chatList.size) {
				// have enough chats in the chat list or chat list is too small
				var offsetOrder = java.lang.Long.MAX_VALUE
				var offsetChatId: Long = 0
				if (!chatList.isEmpty()) {
					val last = chatList.last()
					offsetOrder = last.order
					offsetChatId = last.chatId
				}
				client?.send(TdApi.GetChats(offsetOrder, offsetChatId, CHATS_LIMIT - chatList.size)) { obj ->
					when (obj.constructor) {
						TdApi.Error.CONSTRUCTOR -> {
							val error = obj as TdApi.Error
							if (error.code != IGNORED_ERROR_CODE) {
								listener?.onTelegramError(error.code, error.message)
							}
						}
						TdApi.Chats.CONSTRUCTOR -> {
							val chatIds = (obj as TdApi.Chats).chatIds
							if (chatIds.isEmpty()) {
								synchronized(chatList) {
									haveFullChatList = true
								}
							}
							// chats had already been received through updates, let's retry request
							requestChats(false, this@TelegramHelper::scanChatsHistory)
							onComplete?.invoke()
						}
						else -> listener?.onTelegramError(-1, "Receive wrong response from TDLib: $obj")
					}
				}
				return
			}
		}
		listener?.onTelegramChatsRead()
	}

	private fun requestBasicGroupFullInfo(id: Int) {
		client?.send(TdApi.GetBasicGroupFullInfo(id)) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.BasicGroupFullInfo.CONSTRUCTOR -> {
					val info = obj as TdApi.BasicGroupFullInfo
					basicGroupsFullInfo[id] = info
					fullInfoUpdatesListeners.forEach { it.onBasicGroupFullInfoUpdated(id, info) }
				}
			}
		}
	}

	fun sendViaBotLocationMessage(userId: Int, shareInfo: TelegramSettings.ShareChatInfo, location: TdApi.Location, device: TelegramSettings.DeviceBot, shareType:String) {
		log.debug("sendViaBotLocationMessage - ${shareInfo.chatId}")
		client?.send(TdApi.GetInlineQueryResults(userId, shareInfo.chatId, location, device.deviceName, "")) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					} else {
						shareInfo.shouldSendViaBotMessage = true
					}
				}
				TdApi.InlineQueryResults.CONSTRUCTOR -> {
					sendViaBotMessageFromQueryResults(shareInfo, obj as TdApi.InlineQueryResults, device.externalId, shareType)
				}
			}
		}
	}

	private fun sendViaBotMessageFromQueryResults(
		shareInfo: TelegramSettings.ShareChatInfo,
		inlineQueryResults: TdApi.InlineQueryResults,
		deviceId: String,
		shareType: String
	) {
		val queryResults = inlineQueryResults.results.asList()
		if (queryResults.isNotEmpty()) {
			val resultArticles = mutableListOf<TdApi.InlineQueryResultArticle>()
			queryResults.forEach {
				if (it is TdApi.InlineQueryResultArticle && it.id.substring(1) == deviceId) {
					val textLocationArticle = it.id.startsWith("t")
					val mapLocationArticle = it.id.startsWith("m")
					if (shareType == SHARE_TYPE_MAP && mapLocationArticle
						|| shareType == SHARE_TYPE_TEXT && textLocationArticle
						|| shareType == SHARE_TYPE_MAP_AND_TEXT && (textLocationArticle || mapLocationArticle)) {
						resultArticles.add(it)
					}
				}
			}
			resultArticles.forEach {
				client?.send(TdApi.SendInlineQueryResultMessage(shareInfo.chatId, 0, true,
					true, inlineQueryResults.inlineQueryId, it.id)) { obj ->
					handleTextLocationMessageUpdate(obj, shareInfo, null)
				}
			}
		}
	}

	private fun requestSupergroupFullInfo(id: Int) {
		client?.send(TdApi.GetSupergroupFullInfo(id)) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.SupergroupFullInfo.CONSTRUCTOR -> {
					val info = obj as TdApi.SupergroupFullInfo
					supergroupsFullInfo[id] = info
					fullInfoUpdatesListeners.forEach { it.onSupergroupFullInfoUpdated(id, info) }
				}
			}
		}
	}

	private fun requestCurrentUser(){
		client?.send(TdApi.GetMe()) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.User.CONSTRUCTOR -> {
					val currUser = obj as TdApi.User
					currentUser = currUser
					if (!hasLocalUserPhoto(currUser) && hasRemoteUserPhoto(currUser)) {
						requestUserPhoto(currUser)
					}
				}
			}
		}
	}

	private fun requestContacts(){
		client?.send(TdApi.GetContacts()) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.Users.CONSTRUCTOR -> {
					val usersIds = obj as TdApi.Users
					usersIds.userIds.forEach {
						requestUser(it)
					}
				}
			}
		}
	}

	fun scanChatsHistory() {
		log.debug("scanChatsHistory: chatList: ${chatList.size}")
		chatList.forEach {
			scanChatHistory(it.chatId, 0, 0, 100)
		}
	}

	private fun scanChatHistory(
		chatId: Long,
		fromMessageId: Long,
		offset: Int,
		limit: Int,
		onlyLocal: Boolean = false
	) {
		client?.send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, onlyLocal)) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.Messages.CONSTRUCTOR -> {
					val messages = (obj as TdApi.Messages).messages
					log.debug("scanChatHistory: chatId: $chatId fromMessageId: $fromMessageId size: ${messages.size}")
					if (messages.isNotEmpty()) {
						messages.forEach {
							addNewMessage(it)
						}
						val lastMessage = messages.last()
						val currentTime = System.currentTimeMillis() / 1000
						if (currentTime-Math.max(lastMessage.date, lastMessage.editDate) < MAX_LOCATION_MESSAGE_HISTORY_SCAN_SEC) {
							scanChatHistory(chatId, lastMessage.id, 0, 100)
							log.debug("scanChatHistory searchMessageId: ${lastMessage.id}")
						} else {
							log.debug("scanChatHistory finishForChat: $chatId")
						}
					}
				}
			}
		}
	}

	private fun requestUser(id: Int) {
		client?.send(TdApi.GetUser(id)) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.User.CONSTRUCTOR -> {
					val user = obj as TdApi.User
					contacts[user.id] = user
					if (!hasLocalUserPhoto(user) && hasRemoteUserPhoto(user)) {
						requestUserPhoto(user)
					}
				}
			}
		}
	}

	fun createPrivateChatWithUser(
		userId: Int,
		shareInfo: TelegramSettings.ShareChatInfo,
		shareChatsInfo: ConcurrentHashMap<Long, TelegramSettings.ShareChatInfo>
	) {
		client?.send(TdApi.CreatePrivateChat(userId, false)) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						shareInfo.hasSharingError = true
						listener?.onTelegramError(error.code, error.message)
					}
				}
				TdApi.Chat.CONSTRUCTOR -> {
					shareInfo.chatId = (obj as TdApi.Chat).id
					shareChatsInfo[shareInfo.chatId] = shareInfo
					listener?.onTelegramChatCreated(obj)
				}
			}
		}
	}

	fun loadMessage(chatId: Long, messageId: Long) {
		requestMessage(chatId, messageId, this@TelegramHelper::addNewMessage)
	}

	private fun requestMessage(chatId: Long, messageId: Long, onComplete: (TdApi.Message) -> Unit) {
		client?.send(TdApi.GetMessage(chatId, messageId)) { obj ->
			if (obj is TdApi.Message) {
				onComplete(obj)
			}
		}
	}

	private fun addNewMessage(message: TdApi.Message) {
		lastTelegramUpdateTime = Math.max(lastTelegramUpdateTime, Math.max(message.date, message.editDate))
		if (message.isAppropriate()) {
			log.debug("addNewMessage: ${message.id}")
			val fromBot = isOsmAndBot(message.senderUserId)
			val viaBot = isOsmAndBot(message.viaBotUserId)
			val oldContent = message.content
			if (oldContent is TdApi.MessageText) {
				if (oldContent.text.text.startsWith(DEVICE_PREFIX)) {
					message.content = parseTextLocation(oldContent.text)
				} else if (oldContent.text.text.startsWith(USER_TEXT_LOCATION_TITLE)) {
					message.content = parseTextLocation(oldContent.text, false)
				}
			} else if (oldContent is TdApi.MessageLocation && (fromBot || viaBot)) {
				message.content = parseOsmAndBotLocation(message)
			}
			if (message.isOutgoing) {
				outgoingMessagesListeners.forEach {
					it.onUpdateMessages(listOf(message))
				}
			} else {
				removeOldMessages(message, fromBot, viaBot)
				val oldMessage = usersLocationMessages.values.firstOrNull { getSenderMessageId(it) == getSenderMessageId(message) && !fromBot && !viaBot }
				val hasNewerMessage = oldMessage != null && (Math.max(message.editDate, message.date) < Math.max(oldMessage.editDate, oldMessage.date))
				if (!hasNewerMessage) {
					usersLocationMessages[message.id] = message
				}
				incomingMessagesListeners.forEach {
					if (!hasNewerMessage) {
						it.onReceiveChatLocationMessages(message.chatId, message)
					}
				}
			}
		}
	}

	private fun removeOldMessages(newMessage: TdApi.Message, fromBot: Boolean, viaBot: Boolean) {
		val iterator = usersLocationMessages.entries.iterator()
		while (iterator.hasNext()) {
			val message = iterator.next().value
			if (newMessage.chatId == message.chatId) {
				val sameSender = getSenderMessageId(newMessage) == getSenderMessageId(message)
				val viaSameBot = newMessage.viaBotUserId == message.viaBotUserId
				if (fromBot || viaBot) {
					if ((fromBot && sameSender) || (viaBot && viaSameBot)) {
						val newCont = newMessage.content
						val cont = message.content
						if (newCont is MessageOsmAndBotLocation && cont is MessageOsmAndBotLocation) {
							if (newCont.name == cont.name) {
								iterator.remove()
							}
						}
					}
				} else if (sameSender && isUserLocationMessage(message) && isUserLocationMessage(newMessage)
					&& Math.max(newMessage.editDate, newMessage.date) > Math.max(message.editDate, message.date)) {
					iterator.remove()
				}
			}
		}
	}

	/**
	 * @chatId Id of the chat
	 * @livePeriod Period for which the location can be updated, in seconds; should be between 60 and 86400 for a live location and 0 otherwise.
	 * @latitude Latitude of the location
	 * @longitude Longitude of the location
	 */
	fun sendLiveLocationMessage(chatsShareInfo:Map<Long, TelegramSettings.ShareChatInfo>, latitude: Double, longitude: Double): Boolean {
		if (!requestingActiveLiveLocationMessages && haveAuthorization) {
			if (needRefreshActiveLiveLocationMessages) {
				getActiveLiveLocationMessages {
					sendLiveLocationImpl(chatsShareInfo, latitude, longitude)
				}
				needRefreshActiveLiveLocationMessages = false
			} else {
				sendLiveLocationImpl(chatsShareInfo, latitude, longitude)
			}
			return true
		}
		return false
	}

	fun stopSendingLiveLocationToChat(shareInfo: TelegramSettings.ShareChatInfo) {
		if (shareInfo.currentMapMessageId != -1L && shareInfo.chatId != -1L) {
			client?.send(
				TdApi.EditMessageLiveLocation(shareInfo.chatId, shareInfo.currentMapMessageId, null, null)) { obj ->
				handleMapLocationMessageUpdate(obj, shareInfo, null)
			}
		}
		needRefreshActiveLiveLocationMessages = true
	}

	fun stopSendingLiveLocationMessages(chatsShareInfo: Map<Long, TelegramSettings.ShareChatInfo>) {
		chatsShareInfo.forEach { (_, chatInfo) ->
			stopSendingLiveLocationToChat(chatInfo)
		}
	}

	fun getActiveLiveLocationMessages(onComplete: (() -> Unit)?) {
		requestingActiveLiveLocationMessages = true
		client?.send(TdApi.GetActiveLiveLocationMessages()) { obj ->
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					val error = obj as TdApi.Error
					if (error.code != IGNORED_ERROR_CODE) {
						needRefreshActiveLiveLocationMessages = true
						outgoingMessagesListeners.forEach {
							it.onSendLiveLocationError(error.code, error.message)
						}
					}
				}
				TdApi.Messages.CONSTRUCTOR -> {
					val messages = (obj as TdApi.Messages).messages
					if (messages.isNotEmpty()) {
						log.debug("getActiveLiveLocationMessages: $messages")
						outgoingMessagesListeners.forEach {
							it.onUpdateMessages(messages.asList())
						}
					}
					onComplete?.invoke()
				}
				else -> outgoingMessagesListeners.forEach {
					it.onSendLiveLocationError(-1, "Receive wrong response from TDLib: $obj")
				}
			}
			requestingActiveLiveLocationMessages = false
		}
	}

	private fun recreateLiveLocationMessage(
		shareInfo: TelegramSettings.ShareChatInfo,
		content: TdApi.InputMessageContent,locationMessage: MessagesDbHelper.LocationMessage?
	) {
		if (shareInfo.chatId != -1L) {
			val array = LongArray(1)
			if (content is TdApi.InputMessageLocation) {
				array[0] = shareInfo.currentMapMessageId
			} else if (content is TdApi.InputMessageText) {
				array[0] = shareInfo.currentTextMessageId
			}
			if (array[0] != 0L) {
				log.debug("recreateLiveLocationMessage - ${array[0]}")
				client?.send(TdApi.DeleteMessages(shareInfo.chatId, array, true)) { obj ->
					when (obj.constructor) {
						TdApi.Ok.CONSTRUCTOR -> sendNewLiveLocationMessage(shareInfo, content,locationMessage)
						TdApi.Error.CONSTRUCTOR -> {
							val error = obj as TdApi.Error
							if (error.code != IGNORED_ERROR_CODE) {
								needRefreshActiveLiveLocationMessages = true
								outgoingMessagesListeners.forEach {
									it.onSendLiveLocationError(error.code, error.message)
								}
							}
						}
					}
				}
			}
		}
		needRefreshActiveLiveLocationMessages = true
	}

	private fun sendNewLiveLocationMessage(shareInfo: TelegramSettings.ShareChatInfo, content: TdApi.InputMessageContent, locationMessage: MessagesDbHelper.LocationMessage?) {
		needRefreshActiveLiveLocationMessages = true
		log.debug("sendNewLiveLocationMessage")
		client?.send(
			TdApi.SendMessage(shareInfo.chatId, 0, false, true, null, content)) { obj ->
			handleMapLocationMessageUpdate(obj, shareInfo, locationMessage)
		}
	}

	private fun sendLiveLocationImpl(chatsShareInfo: Map<Long, TelegramSettings.ShareChatInfo>, latitude: Double, longitude: Double) {
		val location = TdApi.Location(latitude, longitude)
		chatsShareInfo.forEach { (chatId, shareInfo) ->
			if (shareInfo.getChatLiveMessageExpireTime() <= 0) {
				return@forEach
			}
			val livePeriod =
				if (shareInfo.currentMessageLimit > (shareInfo.start + MAX_LOCATION_MESSAGE_LIVE_PERIOD_SEC)) {
					MAX_LOCATION_MESSAGE_LIVE_PERIOD_SEC
				} else {
					shareInfo.livePeriod.toInt()
				}
			val content = TdApi.InputMessageLocation(location, livePeriod)
			val msgId = shareInfo.currentMapMessageId
			val timeAfterLastSendMessage = ((System.currentTimeMillis() / 1000) - shareInfo.lastSendMapMessageTime)
			log.debug("sendLiveLocationImpl - $msgId pendingMapMessage ${shareInfo.pendingMapMessage}")
			if (msgId != -1L) {
				if (shareInfo.shouldDeletePreviousMapMessage) {
					recreateLiveLocationMessage(shareInfo, content, null)
					shareInfo.shouldDeletePreviousMapMessage = false
					shareInfo.currentMapMessageId = -1
				} else {
					log.debug("EditMessageLiveLocation - $msgId")
					client?.send(
						TdApi.EditMessageLiveLocation(chatId, msgId, null, location)) { obj ->
						handleMapLocationMessageUpdate(obj, shareInfo, null)
					}
				}
			} else if (!shareInfo.pendingMapMessage || shareInfo.pendingMapMessage && timeAfterLastSendMessage > SEND_NEW_MESSAGE_INTERVAL_SEC) {
				sendNewLiveLocationMessage(shareInfo, content, null)
			}
		}
	}

	fun sendLiveLocationMap(shareInfo: TelegramSettings.ShareChatInfo, locationMessage: MessagesDbHelper.LocationMessage) {
		val location = TdApi.Location(locationMessage.lat, locationMessage.lon)
			if (shareInfo.getChatLiveMessageExpireTime() <= 0) {
				return
			}
			val livePeriod =
				if (shareInfo.currentMessageLimit > (shareInfo.start + MAX_LOCATION_MESSAGE_LIVE_PERIOD_SEC)) {
					MAX_LOCATION_MESSAGE_LIVE_PERIOD_SEC
				} else {
					shareInfo.livePeriod.toInt()
				}
			val content = TdApi.InputMessageLocation(location, livePeriod)
			val msgId = shareInfo.currentMapMessageId
			val timeAfterLastSendMessage = ((System.currentTimeMillis() / 1000) - shareInfo.lastSendMapMessageTime)
			log.debug("sendLiveLocationImpl - $msgId pendingMapMessage ${shareInfo.pendingMapMessage}")
			mapIndex++
			if (msgId != -1L) {
				if (shareInfo.shouldDeletePreviousMapMessage) {
					recreateLiveLocationMessage(shareInfo, content, locationMessage)
					shareInfo.shouldDeletePreviousMapMessage = false
					shareInfo.currentMapMessageId = -1
				} else {
					log.debug("EditMessageLiveLocation - $msgId")
					client?.send(
						TdApi.EditMessageLiveLocation(shareInfo.chatId, msgId, null, location)) { obj ->
						handleMapLocationMessageUpdate(obj, shareInfo, locationMessage)
					}
				}
			} else if (!shareInfo.pendingMapMessage || shareInfo.pendingMapMessage && timeAfterLastSendMessage > SEND_NEW_MESSAGE_INTERVAL_SEC) {
				sendNewLiveLocationMessage(shareInfo, content, locationMessage)
			}
	}

	fun sendLiveLocationText(chatsShareInfo: Map<Long, TelegramSettings.ShareChatInfo>, location: Location) {
		chatsShareInfo.forEach { (chatId, shareInfo) ->
			if (shareInfo.getChatLiveMessageExpireTime() <= 0) {
				return@forEach
			}
			val msgId = shareInfo.currentTextMessageId
			if (msgId == -1L) {
				shareInfo.updateTextMessageId = 1
			}
			val content = getTextMessageContent(shareInfo.updateTextMessageId, location)
			val timeAfterLastSendMessage = ((System.currentTimeMillis() / 1000) - shareInfo.lastSendTextMessageTime)
			log.debug("sendLiveLocationText - $msgId pendingMapMessage ${shareInfo.pendingTextMessage}")
			if (msgId != -1L) {
				if (shareInfo.shouldDeletePreviousTextMessage) {
					recreateLiveLocationMessage(shareInfo, content, null)
					shareInfo.shouldDeletePreviousTextMessage = false
				} else {
					client?.send(TdApi.EditMessageText(chatId, msgId, null, content)) { obj ->
						handleTextLocationMessageUpdate(obj, shareInfo, null)
					}
				}
			} else if (!shareInfo.pendingTextMessage || shareInfo.pendingTextMessage && timeAfterLastSendMessage > SEND_NEW_MESSAGE_INTERVAL_SEC) {
				client?.send(TdApi.SendMessage(chatId, 0, false, false, null, content)) { obj ->
					handleTextLocationMessageUpdate(obj, shareInfo, null)
				}
			}
		}
	}

	fun sendLiveLocationText(shareInfo: TelegramSettings.ShareChatInfo, location: MessagesDbHelper.LocationMessage) {
			if (shareInfo.getChatLiveMessageExpireTime() <= 0) {
				return
			}
			val msgId = shareInfo.currentTextMessageId
			if (msgId == -1L) {
				shareInfo.updateTextMessageId = 1
			}
			val content = getTextMessageContent(shareInfo.updateTextMessageId, location)
			val timeAfterLastSendMessage = ((System.currentTimeMillis() / 1000) - shareInfo.lastSendTextMessageTime)
			log.debug("sendLiveLocationText - $msgId pendingMapMessage ${shareInfo.pendingTextMessage}")
			textIndex++
			if (msgId != -1L) {
				if (shareInfo.shouldDeletePreviousTextMessage) {
					recreateLiveLocationMessage(shareInfo, content, location)
					shareInfo.shouldDeletePreviousTextMessage = false
				} else {
					client?.send(TdApi.EditMessageText(shareInfo.chatId, msgId, null, content)) { obj ->
						handleTextLocationMessageUpdate(obj, shareInfo, location)
					}
				}
			} else if (!shareInfo.pendingTextMessage || shareInfo.pendingTextMessage && timeAfterLastSendMessage > SEND_NEW_MESSAGE_INTERVAL_SEC) {
				client?.send(TdApi.SendMessage(shareInfo.chatId, 0, false, false, null, content)) { obj ->
					handleTextLocationMessageUpdate(obj, shareInfo, location)
				}
			}
	}

	private fun handleMapLocationMessageUpdate(obj: TdApi.Object, shareInfo: TelegramSettings.ShareChatInfo, location: MessagesDbHelper.LocationMessage?) {
		handleMapIndex++
		when (obj.constructor) {
			TdApi.Error.CONSTRUCTOR -> {
				val error = obj as TdApi.Error
				needRefreshActiveLiveLocationMessages = true
				if (error.code == MESSAGE_CANNOT_BE_EDITED_ERROR_CODE) {
					shareInfo.shouldDeletePreviousMapMessage = true
				} else if (error.code != IGNORED_ERROR_CODE) {
					shareInfo.hasSharingError = true
					outgoingMessagesListeners.forEach {
						it.onSendLiveLocationError(error.code, error.message)
					}
				}
			}
			TdApi.Message.CONSTRUCTOR -> {
				if (obj is TdApi.Message) {
					when {
						obj.sendingState?.constructor == TdApi.MessageSendingStateFailed.CONSTRUCTOR -> {
							shareInfo.hasSharingError = true
							needRefreshActiveLiveLocationMessages = true
							location?.status = MessagesDbHelper.LocationMessage.STATUS_ERROR
							outgoingMessagesListeners.forEach {
								it.onSendLiveLocationError(-1, "Map location message ${obj.id} failed to send")
							}
						}
						obj.sendingState?.constructor == TdApi.MessageSendingStatePending.CONSTRUCTOR -> {
							shareInfo.pendingMapMessage = true
							shareInfo.lastSendMapMessageTime = obj.date
							location?.status = MessagesDbHelper.LocationMessage.STATUS_PENDING
							log.debug("handleMapLocationMessageUpdate - MessageSendingStatePending")
						}
						else -> {
							shareInfo.hasSharingError = false
							location?.status = MessagesDbHelper.LocationMessage.STATUS_SENT
							outgoingMessagesListeners.forEach {
								it.onUpdateMessages(listOf(obj))
							}
						}
					}
				}
			}
		}
	}

	private fun handleTextLocationMessageUpdate(obj: TdApi.Object, shareInfo: TelegramSettings.ShareChatInfo, location: MessagesDbHelper.LocationMessage?) {
		handleTextIndex++
		when (obj.constructor) {
			TdApi.Error.CONSTRUCTOR -> {
				val error = obj as TdApi.Error
				if (error.code == MESSAGE_CANNOT_BE_EDITED_ERROR_CODE) {
					shareInfo.shouldDeletePreviousTextMessage = true
				} else if (error.code != IGNORED_ERROR_CODE) {
					shareInfo.hasSharingError = true
					outgoingMessagesListeners.forEach {
						it.onSendLiveLocationError(error.code, error.message)
					}
				}
			}
			TdApi.Message.CONSTRUCTOR -> {
				if (obj is TdApi.Message) {
					when {
						obj.sendingState?.constructor == TdApi.MessageSendingStateFailed.CONSTRUCTOR -> {
							shareInfo.hasSharingError = true
							needRefreshActiveLiveLocationMessages = true
							location?.status = MessagesDbHelper.LocationMessage.STATUS_ERROR
							outgoingMessagesListeners.forEach {
								it.onSendLiveLocationError(-1, "Text location message ${obj.id} failed to send")
							}
						}
						obj.sendingState?.constructor == TdApi.MessageSendingStatePending.CONSTRUCTOR -> {
							shareInfo.pendingTextMessage = true
							shareInfo.lastSendTextMessageTime = obj.date
							location?.status = MessagesDbHelper.LocationMessage.STATUS_PENDING
							log.debug("handleTextLocationMessageUpdate - MessageSendingStatePending")
						}
						else -> {
							shareInfo.hasSharingError = false
							location?.status = MessagesDbHelper.LocationMessage.STATUS_SENT
							outgoingMessagesListeners.forEach {
								it.onUpdateMessages(listOf(obj))
							}
						}
					}
				}
			}
		}
	}

	private fun formatLocation(sig: Location): String {
		return String.format(Locale.US, "%.5f, %.5f", sig.latitude, sig.longitude)
	}

	private fun formatLocation(sig: MessagesDbHelper.LocationMessage): String {
		return String.format(Locale.US, "%.5f, %.5f", sig.lat, sig.lon)
	}

	private fun formatFullTime(ti: Long): String {
		val dt = Date(ti)
		return UTC_DATE_FORMAT.format(dt) + " " + UTC_TIME_FORMAT.format(dt) + " UTC"
	}

	private fun getTextMessageContent(updateId: Int, location: Location): TdApi.InputMessageText {
		val entities = mutableListOf<TdApi.TextEntity>()
		val builder = StringBuilder()
		val locationMessage = formatLocation(location)

		val firstSpace = USER_TEXT_LOCATION_TITLE.indexOf(' ')
		val secondSpace = USER_TEXT_LOCATION_TITLE.indexOf(' ', firstSpace + 1)
		entities.add(TdApi.TextEntity(builder.length + firstSpace + 1, secondSpace - firstSpace, TdApi.TextEntityTypeTextUrl(SHARING_LINK)))
		builder.append("$USER_TEXT_LOCATION_TITLE\n")

		entities.add(TdApi.TextEntity(builder.lastIndex, LOCATION_PREFIX.length, TdApi.TextEntityTypeBold()))
		builder.append(LOCATION_PREFIX)

		entities.add(TdApi.TextEntity(builder.length, locationMessage.length,
			TdApi.TextEntityTypeTextUrl("$BASE_SHARING_URL?lat=${location.latitude}&lon=${location.longitude}")))
		builder.append("$locationMessage\n")

		if (location.hasAltitude() && location.altitude != 0.0) {
			entities.add(TdApi.TextEntity(builder.lastIndex, ALTITUDE_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$ALTITUDE_PREFIX%.1f m\n", location.altitude))
		}
		if (location.hasSpeed() && location.speed > 0) {
			entities.add(TdApi.TextEntity(builder.lastIndex, SPEED_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$SPEED_PREFIX%.1f m/s\n", location.speed))
		}
		if (location.hasAccuracy() && location.accuracy != 0.0f && location.speed == 0.0f) {
			entities.add(TdApi.TextEntity(builder.lastIndex, HDOP_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$HDOP_PREFIX%d m\n", location.accuracy.toInt()))
		}
		if (updateId == 0) {
			builder.append(String.format("$UPDATED_PREFIX%s\n", formatFullTime(location.time)))
		} else {
			builder.append(String.format("$UPDATED_PREFIX%s (%d)\n", formatFullTime(location.time), updateId))
		}
		val textMessage = builder.toString().trim()

		return TdApi.InputMessageText(TdApi.FormattedText(textMessage, entities.toTypedArray()), true, true)
	}

	private fun getTextMessageContent(updateId: Int, location: MessagesDbHelper.LocationMessage): TdApi.InputMessageText {
		val entities = mutableListOf<TdApi.TextEntity>()
		val builder = StringBuilder()
		val locationMessage = formatLocation(location)

		val firstSpace = USER_TEXT_LOCATION_TITLE.indexOf(' ')
		val secondSpace = USER_TEXT_LOCATION_TITLE.indexOf(' ', firstSpace + 1)
		entities.add(TdApi.TextEntity(builder.length + firstSpace + 1, secondSpace - firstSpace, TdApi.TextEntityTypeTextUrl(SHARING_LINK)))
		builder.append("$USER_TEXT_LOCATION_TITLE\n")

		entities.add(TdApi.TextEntity(builder.lastIndex, LOCATION_PREFIX.length, TdApi.TextEntityTypeBold()))
		builder.append(LOCATION_PREFIX)

		entities.add(TdApi.TextEntity(builder.length, locationMessage.length,
			TdApi.TextEntityTypeTextUrl("$BASE_SHARING_URL?lat=${location.lat}&lon=${location.lon}")))
		builder.append("$locationMessage\n")

		if (location.altitude != 0.0) {
			entities.add(TdApi.TextEntity(builder.lastIndex, ALTITUDE_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$ALTITUDE_PREFIX%.1f m\n", location.altitude))
		}
		if (location.speed > 0) {
			entities.add(TdApi.TextEntity(builder.lastIndex, SPEED_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$SPEED_PREFIX%.1f m/s\n", location.speed))
		}
		if (location.hdop != 0.0 && location.speed == 0.0) {
			entities.add(TdApi.TextEntity(builder.lastIndex, HDOP_PREFIX.length, TdApi.TextEntityTypeBold()))
			builder.append(String.format(Locale.US, "$HDOP_PREFIX%d m\n", location.hdop.toInt()))
		}
		if (updateId == 0) {
			builder.append(String.format("$UPDATED_PREFIX%s\n", formatFullTime(location.date)))
		} else {
			builder.append(String.format("$UPDATED_PREFIX%s (%d)\n", formatFullTime(location.date), updateId))
		}
		val textMessage = builder.toString().trim()

		return TdApi.InputMessageText(TdApi.FormattedText(textMessage, entities.toTypedArray()), true, true)
	}

	fun logout(): Boolean {
		return if (libraryLoaded) {
			haveAuthorization = false
			client!!.send(TdApi.LogOut(), defaultHandler)
			true
		} else {
			false
		}
	}

	fun close(): Boolean {
		return if (libraryLoaded) {
			haveAuthorization = false
			client!!.send(TdApi.Close(), defaultHandler)
			true
		} else {
			false
		}
	}

	private fun setChatOrder(chat: TdApi.Chat, order: Long) {
		synchronized(chatList) {
			val isChannel = isChannel(chat)

			if (chat.order != 0L) {
				chatList.remove(OrderedChat(chat.order, chat.id, isChannel))
			}

			chat.order = order

			if (chat.order != 0L) {
				chatList.add(OrderedChat(chat.order, chat.id, isChannel))
			}
		}
	}

	private fun onAuthorizationStateUpdated(authorizationState: AuthorizationState?, info: Boolean = false) {
		val prevAuthState = getTelegramAuthorizationState()
		if (authorizationState != null) {
			this.authorizationState = authorizationState
		}
		when (this.authorizationState?.constructor) {
			TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
				if (!info) {
					log.info("Init tdlib parameters")

					val parameters = TdApi.TdlibParameters()
					parameters.databaseDirectory = File(appDir, "tdlib").absolutePath
					parameters.useMessageDatabase = true
					parameters.useSecretChats = true
					parameters.apiId = 293148
					parameters.apiHash = "d1942abd0f1364efe5020e2bfed2ed15"
					parameters.systemLanguageCode = "en"
					parameters.deviceModel = "Android"
					parameters.systemVersion = "OsmAnd Telegram"
					parameters.applicationVersion = "1.0"
					parameters.enableStorageOptimizer = true

					client!!.send(TdApi.SetTdlibParameters(parameters), AuthorizationRequestHandler())
				}
			}
			TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR -> {
				if (!info) {
					client!!.send(TdApi.CheckDatabaseEncryptionKey(), AuthorizationRequestHandler())
				}
			}
			TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
				log.info("Request phone number")
				telegramAuthorizationRequestHandler?.telegramAuthorizationRequestListener?.onRequestTelegramAuthenticationParameter(PHONE_NUMBER)
			}
			TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
				log.info("Request code")
				telegramAuthorizationRequestHandler?.telegramAuthorizationRequestListener?.onRequestTelegramAuthenticationParameter(CODE)
			}
			TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
				log.info("Request password")
				telegramAuthorizationRequestHandler?.telegramAuthorizationRequestListener?.onRequestTelegramAuthenticationParameter(PASSWORD)
			}
			TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
				log.info("Ready")
			}
			TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
				log.info("Logging out")
			}
			TdApi.AuthorizationStateClosing.CONSTRUCTOR -> {
				log.info("Closing")
			}
			TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
				log.info("Closed")
			}
			else -> log.error("Unsupported authorization state: " + this.authorizationState!!)
		}
		val wasAuthorized = haveAuthorization
		haveAuthorization = this.authorizationState?.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
		if (wasAuthorized != haveAuthorization) {
			needRefreshActiveLiveLocationMessages = true
			if (haveAuthorization) {
				requestChats(true, null)
				requestCurrentUser()
				requestContacts()
			}
		}
		val newAuthState = getTelegramAuthorizationState()
		listener?.onTelegramStatusChanged(prevAuthState, newAuthState)
	}

	private fun TdApi.Message.isAppropriate(): Boolean {
		if (isChannelPost) {
			return false
		}
		val content = content
		val isUserTextLocation = (content is TdApi.MessageText) && content.text.text.startsWith(USER_TEXT_LOCATION_TITLE)
		val isOsmAndBot = isOsmAndBot(senderUserId) || isOsmAndBot(viaBotUserId)
		if (!(isUserTextLocation || content is TdApi.MessageLocation || isOsmAndBot)) {
			return false
		}
		val lastEdited = Math.max(date, editDate)
		if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - lastEdited > messageActiveTimeSec) {
			return false
		}

		return when (content) {
			is TdApi.MessageLocation -> true
			is TdApi.MessageText -> (isOsmAndBot) && content.text.text.startsWith(DEVICE_PREFIX) || isUserTextLocation
			else -> false
		}
	}

	class OrderedChat internal constructor(internal val order: Long, internal val chatId: Long, internal val isChannel: Boolean) : Comparable<OrderedChat> {

		override fun compareTo(other: OrderedChat): Int {
			if (this.order != other.order) {
				return if (other.order < this.order) -1 else 1
			}
			return if (this.chatId != other.chatId) {
				if (other.chatId < this.chatId) -1 else 1
			} else 0
		}

		override fun equals(other: Any?): Boolean {
			if (other == null) {
				return false
			}
			if (other !is OrderedChat) {
				return false
			}
			val o = other as OrderedChat?
			return this.order == o!!.order && this.chatId == o.chatId
		}

		override fun hashCode(): Int {
			return (order + chatId).hashCode()
		}
	}

	private class DefaultHandler : ResultHandler {
		override fun onResult(obj: TdApi.Object) {}
	}

	private inner class UpdatesHandler : ResultHandler {
		override fun onResult(obj: TdApi.Object) {
			when (obj.constructor) {
				TdApi.UpdateAuthorizationState.CONSTRUCTOR -> onAuthorizationStateUpdated((obj as TdApi.UpdateAuthorizationState).authorizationState)

				TdApi.UpdateUser.CONSTRUCTOR -> {
					val updateUser = obj as TdApi.UpdateUser
					val user = updateUser.user
					users[updateUser.user.id] = user
					if (user.outgoingLink is TdApi.LinkStateIsContact) {
						contacts[user.id] = user
					}
					if (isOsmAndBot(user.id)) {
						osmandBot = user
					}
				}
				TdApi.UpdateUserStatus.CONSTRUCTOR -> {
					val updateUserStatus = obj as TdApi.UpdateUserStatus
					val user = users[updateUserStatus.userId]
					synchronized(user!!) {
						user.status = updateUserStatus.status
					}
				}
				TdApi.UpdateBasicGroup.CONSTRUCTOR -> {
					val updateBasicGroup = obj as TdApi.UpdateBasicGroup
					basicGroups[updateBasicGroup.basicGroup.id] = updateBasicGroup.basicGroup
				}
				TdApi.UpdateSupergroup.CONSTRUCTOR -> {
					val updateSupergroup = obj as TdApi.UpdateSupergroup
					supergroups[updateSupergroup.supergroup.id] = updateSupergroup.supergroup
				}
				TdApi.UpdateSecretChat.CONSTRUCTOR -> {
					val updateSecretChat = obj as TdApi.UpdateSecretChat
					secretChats[updateSecretChat.secretChat.id] = updateSecretChat.secretChat
				}

				TdApi.UpdateNewChat.CONSTRUCTOR -> {
					val updateNewChat = obj as TdApi.UpdateNewChat
					val chat = updateNewChat.chat
					synchronized(chat) {
						chats[chat.id] = chat
						val localPhoto = chat.photo?.small?.local
						val hasLocalPhoto = if (localPhoto != null) {
							localPhoto.canBeDownloaded && localPhoto.isDownloadingCompleted && localPhoto.path.isNotEmpty()
						} else {
							false
						}
						if (!hasLocalPhoto) {
							val remotePhoto = chat.photo?.small?.remote
							if (remotePhoto != null && remotePhoto.id.isNotEmpty()) {
								downloadChatFilesMap[remotePhoto.id] = chat
								client!!.send(TdApi.GetRemoteFile(remotePhoto.id, null)) { obj ->
									when (obj.constructor) {
										TdApi.Error.CONSTRUCTOR -> {
											val error = obj as TdApi.Error
											val code = error.code
											if (code != IGNORED_ERROR_CODE) {
												listener?.onTelegramError(code, error.message)
											}
										}
										TdApi.File.CONSTRUCTOR -> {
											val file = obj as TdApi.File
											client!!.send(TdApi.DownloadFile(file.id, 10), defaultHandler)
										}
										else -> listener?.onTelegramError(-1, "Receive wrong response from TDLib: $obj")
									}
								}
							}
						}
						val order = chat.order
						chat.order = 0
						setChatOrder(chat, order)
					}
					listener?.onTelegramChatsChanged()
				}
				TdApi.UpdateChatTitle.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatTitle
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.title = updateChat.title
						}
						listener?.onTelegramChatChanged(chat)
					}
				}
				TdApi.UpdateChatPhoto.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatPhoto
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.photo = updateChat.photo
						}
						listener?.onTelegramChatChanged(chat)
					}
				}
				TdApi.UpdateChatLastMessage.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatLastMessage
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.lastMessage = updateChat.lastMessage
							setChatOrder(chat, updateChat.order)
						}
						//listener?.onTelegramChatsChanged()
					}
				}
				TdApi.UpdateChatOrder.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatOrder
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							setChatOrder(chat, updateChat.order)
						}
						listener?.onTelegramChatsChanged()
					}
				}
				TdApi.UpdateChatIsPinned.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatIsPinned
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.isPinned = updateChat.isPinned
							setChatOrder(chat, updateChat.order)
						}
						//listener?.onTelegramChatsChanged()
					}
				}
				TdApi.UpdateChatReadInbox.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatReadInbox
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId
							chat.unreadCount = updateChat.unreadCount
						}
					}
				}
				TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatReadOutbox
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId
						}
					}
				}
				TdApi.UpdateChatUnreadMentionCount.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatUnreadMentionCount
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.unreadMentionCount = updateChat.unreadMentionCount
						}
					}
				}
				TdApi.UpdateMessageEdited.CONSTRUCTOR -> {
					val updateMessageEdited = obj as TdApi.UpdateMessageEdited
					val message = usersLocationMessages[updateMessageEdited.messageId]
					if (message == null) {
						updateMessageEdited.apply {
							requestMessage(chatId, messageId, this@TelegramHelper::addNewMessage)
						}
					} else {
						synchronized(message) {
							message.editDate = updateMessageEdited.editDate
							lastTelegramUpdateTime = Math.max(message.date, message.editDate)
						}
						incomingMessagesListeners.forEach {
							it.updateLocationMessages()
						}
					}
				}
				TdApi.UpdateMessageContent.CONSTRUCTOR -> {
					val updateMessageContent = obj as TdApi.UpdateMessageContent
					val message = usersLocationMessages[updateMessageContent.messageId]
					if (message == null) {
						updateMessageContent.apply {
							requestMessage(chatId, messageId, this@TelegramHelper::addNewMessage)
						}
					} else {
						synchronized(message) {
							lastTelegramUpdateTime = Math.max(message.date, message.editDate)
							val newContent = updateMessageContent.newContent
							val fromBot = isOsmAndBot(message.senderUserId)
							val viaBot = isOsmAndBot(message.viaBotUserId)
							message.content = if (newContent is TdApi.MessageText) {
								parseTextLocation(newContent.text, (fromBot || viaBot))
							} else if (newContent is TdApi.MessageLocation &&
								(isOsmAndBot(message.senderUserId) || isOsmAndBot(message.viaBotUserId))) {
								parseOsmAndBotLocationContent(message.content as MessageOsmAndBotLocation, newContent)
							} else {
								newContent
							}
						}
						log.debug("UpdateMessageContent " + message.senderUserId)
						incomingMessagesListeners.forEach {
							it.onReceiveChatLocationMessages(message.chatId, message)
						}
					}
				}
				TdApi.UpdateNewMessage.CONSTRUCTOR -> {
					addNewMessage((obj as TdApi.UpdateNewMessage).message)
				}
				TdApi.UpdateMessageMentionRead.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateMessageMentionRead
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.unreadMentionCount = updateChat.unreadMentionCount
						}
					}
				}
				TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
					needRefreshActiveLiveLocationMessages = true
				}
				TdApi.UpdateDeleteMessages.CONSTRUCTOR -> {
					val updateDeleteMessages = obj as TdApi.UpdateDeleteMessages
					if (updateDeleteMessages.isPermanent) {
						val chatId = updateDeleteMessages.chatId
						val deletedMessages = mutableListOf<TdApi.Message>()
						for (messageId in updateDeleteMessages.messageIds) {
							usersLocationMessages.remove(messageId)
								?.also { deletedMessages.add(it) }
						}
						outgoingMessagesListeners.forEach {
							it.onDeleteMessages(chatId, updateDeleteMessages.messageIds.toList())
						}
						if (deletedMessages.isNotEmpty()) {
							incomingMessagesListeners.forEach {
								it.onDeleteChatLocationMessages(chatId, deletedMessages)
							}
						}
					}
				}
				TdApi.UpdateChatReplyMarkup.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatReplyMarkup
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.replyMarkupMessageId = updateChat.replyMarkupMessageId
						}
					}
				}
				TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> {
					val updateChat = obj as TdApi.UpdateChatDraftMessage
					val chat = chats[updateChat.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.draftMessage = updateChat.draftMessage
							setChatOrder(chat, updateChat.order)
						}
						//listener?.onTelegramChatsChanged()
					}
				}
				TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> {
					val update = obj as TdApi.UpdateChatNotificationSettings
					val chat = chats[update.chatId]
					if (chat != null) {
						synchronized(chat) {
							chat.notificationSettings = update.notificationSettings
						}
					}
				}

				TdApi.UpdateFile.CONSTRUCTOR -> {
					val updateFile = obj as TdApi.UpdateFile
					if (updateFile.file.local.isDownloadingCompleted) {
						val remoteId = updateFile.file.remote.id
						val chat = downloadChatFilesMap.remove(remoteId)
						if (chat != null) {
							synchronized(chat) {
								chat.photo?.small = updateFile.file
							}
							listener?.onTelegramChatChanged(chat)
						}
						val user = downloadUserFilesMap.remove(remoteId)
						if (user != null) {
							synchronized(user) {
								user.profilePhoto?.small = updateFile.file
							}
							listener?.onTelegramUserChanged(user)
						}
					}
				}

				TdApi.UpdateUserFullInfo.CONSTRUCTOR -> {
					val updateUserFullInfo = obj as TdApi.UpdateUserFullInfo
					usersFullInfo[updateUserFullInfo.userId] = updateUserFullInfo.userFullInfo
				}
				TdApi.UpdateBasicGroupFullInfo.CONSTRUCTOR -> {
					val updateBasicGroupFullInfo = obj as TdApi.UpdateBasicGroupFullInfo
					val id = updateBasicGroupFullInfo.basicGroupId
					if (basicGroupsFullInfo.containsKey(id)) {
						val info = updateBasicGroupFullInfo.basicGroupFullInfo
						basicGroupsFullInfo[id] = info
						fullInfoUpdatesListeners.forEach { it.onBasicGroupFullInfoUpdated(id, info) }
					}
				}
				TdApi.UpdateSupergroupFullInfo.CONSTRUCTOR -> {
					val updateSupergroupFullInfo = obj as TdApi.UpdateSupergroupFullInfo
					val id = updateSupergroupFullInfo.supergroupId
					if (supergroupsFullInfo.containsKey(id)) {
						val info = updateSupergroupFullInfo.supergroupFullInfo
						supergroupsFullInfo[id] = info
						fullInfoUpdatesListeners.forEach { it.onSupergroupFullInfoUpdated(id, info) }
					}
				}
				TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
					val udateMessageSendSucceeded = obj as TdApi.UpdateMessageSendSucceeded
					val message = udateMessageSendSucceeded.message
					log.debug("UpdateMessageSendSucceeded: $message")
					outgoingMessagesListeners.forEach {
						it.onUpdateMessages(listOf(message))
					}
				}
			}
		}
	}

	private inner class AuthorizationRequestHandler : ResultHandler {
		override fun onResult(obj: TdApi.Object) {
			when (obj.constructor) {
				TdApi.Error.CONSTRUCTOR -> {
					log.error("Receive an error: $obj")
					val errorObj = obj as TdApi.Error
					if (errorObj.code != IGNORED_ERROR_CODE) {
						telegramAuthorizationRequestHandler?.telegramAuthorizationRequestListener?.onTelegramAuthorizationRequestError(errorObj.code, errorObj.message)
						onAuthorizationStateUpdated(null) // repeat last action
					}
				}
				TdApi.Ok.CONSTRUCTOR -> {
				}
				else -> log.error("Receive wrong response from TDLib: $obj")
			}// result is already received through UpdateAuthorizationState, nothing to do
		}
	}
}