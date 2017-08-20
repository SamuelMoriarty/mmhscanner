package net.lmoriarty.scanner

import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.api.events.IListener
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.*
import sx.blah.discord.util.RateLimitException
import java.util.*
import java.util.concurrent.*
import kotlin.collections.ArrayList
import kotlin.concurrent.timer

/**
 * Handles RateLimitException and retries if encounters. Blocks the thread.
 */
fun <T> makeRequest(action: () -> T): T {
    while (true) {
        try {
            return action()
        } catch (e: RateLimitException) {
            log.info("Hit a rate limit - retrying in ${e.retryDelay} milliseconds.")
            Thread.sleep(e.retryDelay + 5)
        }
    }
}

val messageHeader = """
|```Type "-mmh list" to see which game types are available!```
""".trimMargin()

class ChatBot {
    val watcher: Watcher
    val client: IDiscordClient

    private val notificationTargets = ConcurrentHashMap<Long, NotificationTarget>()
    // used for parallel execution of requests
    private val requestExecutor: ThreadPoolExecutor

    private var owner: IUser? = null

    init {
        val internalThreadFactory = Executors.defaultThreadFactory()
        val listenerThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Handler Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        val requestThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Request Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        // we're going to be blocking in our handlers, so to avoid thread starving
        // we use an unbounded thread pool
        val executor = ThreadPoolExecutor(8, Int.MAX_VALUE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), listenerThreadFactory)
        requestExecutor = ThreadPoolExecutor(16, 16, 0, TimeUnit.SECONDS, LinkedBlockingQueue(), requestThreadFactory)

        val clientBuilder = ClientBuilder()
        clientBuilder.withToken(Settings.token)
        client = clientBuilder.login()

        client.dispatcher.registerListener(executor, IListener<ReadyEvent> {
            handleBotLoad(it)
        })
        client.dispatcher.registerListener(executor, IListener<MessageReceivedEvent> {
            handleMessage(it)
        })

        watcher = Watcher(this)
    }

    private fun commitSettings() {
        Settings.channels.clear()
        for ((id, target) in notificationTargets) {
            val channel = Settings.NotificationChannel()
            channel.types = HashSet(target.types)
            Settings.channels[id] = channel
        }

        Settings.writeSettings()
    }

    private fun retrieveSettings() {
        Settings.readSettings()
        notificationTargets.clear()
        owner = null

        for ((id, notificationChannel) in Settings.channels) {
            val channel = client.getChannelByID(id)
            val target = NotificationTarget(channel, this, HashSet(notificationChannel.types))
            notificationTargets[id] = target
         }

        if (Settings.owner != 0L) {
            val user = client.getUserByID(Settings.owner)
            owner = user
            log.info("Retrieved owner (${user.name}).")
        }
    }

    private fun isNotifiableChannel(channel: IChannel): Boolean {
        return notificationTargets.containsKey(channel.longID)
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        if (user == owner) {
            return true
        }

        val permissions = user.getPermissionsForGuild(guild)
        return permissions.contains(Permissions.ADMINISTRATOR) || permissions.contains(Permissions.MANAGE_SERVER)
    }

    private fun clearMessagesInChannel(channel: IChannel) {
        val toDelete = ArrayList<IMessage>()
        val history = makeRequest { channel.fullMessageHistory }

        log.info("Fetched ${history.size} messages in preparation for deletion.")

        for (historyMessage in history) {
            if (historyMessage.author == client.ourUser) {
                toDelete.add(historyMessage)
            }
        }

        log.info("${toDelete.size} messages will be deleted.")

        // preallocate size just in case
        val futureList = ArrayList<Future<*>>(toDelete.size)
        for (deletableMessage in toDelete) {
            futureList.add(requestExecutor.submit { makeRequest { deletableMessage.delete() } })
        }

        // wait for all tasks to complete
        for (future in futureList) {
            future.get()
        }

        log.info("Deleted ${toDelete.size} messages.")
    }

    private fun commandListGameTypes(message: IMessage) {
        val channel = message.channel

        if (isNotifiableChannel(channel)) {
            var response = "Here's the supported game types, Dave:\n```"

            for (gameType in GameType.values()) {
                response += "${gameType} -> ${gameType.regex}\n"
            }

            response += "```"

            makeRequest { message.channel.sendMessage(response) }
        }
    }

    private fun commandRegisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (!isNotifiableChannel(channel)) {
                notificationTargets[channel.longID] = NotificationTarget(channel, this, HashSet())
                commitSettings()

                makeRequest { channel.sendMessage("Channel registered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun commandUnregisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                notificationTargets.remove(channel.longID)?.kill()
                commitSettings()

                makeRequest { channel.sendMessage("Channel unregistered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun commandClearMessages(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                clearMessagesInChannel(channel)

                makeRequest { channel.sendMessage("I've cleared all my messages, Dave.") }
            }
        }
    }

    private fun dispatchCommand(message: IMessage) {
        val input = message.content
        val split = input.split(" ")

        // nothing to see here
        if (split.size < 2) return

        if (split[0] == "-mmh") {
            val command = split[1]
            //val arg = if (split.size > 2) split.subList(2, split.size).reduce { acc, s -> acc + " " + s } else ""

            when (command) {
                "register" -> commandRegisterChannel(message)
                "unregister" -> commandUnregisterChannel(message)
                "clear" -> commandClearMessages(message)
                "list" -> commandListGameTypes(message)
            }
        }
    }

    fun onGameHosted(gameInfo: GameInfo) {
        log.info("A game has been hosted: ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameCreate(gameInfo)
        }
    }

    fun onGameUpdated(gameInfo: GameInfo) {
        log.info("A game has been updated: ${gameInfo.oldName} -> ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameUpdate(gameInfo)
        }
    }

    fun onGameRemoved(gameInfo: GameInfo) {
        log.info("A game has been removed: ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameRemove(gameInfo)
        }
    }

    private fun handleBotLoad(event: ReadyEvent) {
        retrieveSettings()

        watcher.start()
        log.info("Watcher started.")
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)
    }

    /**
     * We run the updates of each target in a single-threaded pool because
     * we don't want multiple updates to interfere with one another, but we
     * want targets to be independent from each other.
     */
    class NotificationTarget(val channel: IChannel,
                             val bot: ChatBot,
                             val types: MutableSet<GameType> = HashSet()) {
        private var lastMessage: IMessage? = null
        private val executor: ThreadPoolExecutor
        // use a treemap for strict ordering
        private val watchedGames = TreeMap<String, GameInfo>()
        private val updateTimer: Timer

        init {
            val threadFactory = ThreadFactory {
                val thread = Executors.defaultThreadFactory().newThread(it)
                thread.name = "Channel Update Thread (${channel.name})"
                thread.isDaemon = false
                return@ThreadFactory thread
            }

            executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), threadFactory)
            executor.allowCoreThreadTimeOut(false)

            executor.submit {
                log.info("Clearing messages.")
                bot.clearMessagesInChannel(channel)
                log.info("Cleared messages.")
            }

            for ((_, info) in bot.watcher.getAll()) {
                processGameCreate(info)
            }

            updateTimer = timer(initialDelay = 1000, period = 1000, action = {
                log.info("Scheduled additional update.")
                executor.submit{
                    log.info("Periodically updating info message.")
                    updateInfoMessage()
                    log.info("Periodically updated info message.")
                }.get() // wait until we're finished to not spam the queue
            })

            log.info("NotificationTarget created for channel ${channel.name} in ${channel.guild.name}")
        }

        private fun sendInfoMessage(string: String) {
            val lastMessage = lastMessage

            log.info("Updating info message.")
            if (lastMessage == null) {
                makeRequest {
                    log.info("Trying to send new message.")
                    this.lastMessage = channel.sendMessage(string)
                    log.info("Sent new message.")
                }
            } else {
                val history = makeRequest {
                    log.info("Trying to get history.")
                    val history = channel.getMessageHistory(32)
                    log.info("Got history.")
                    history
                }
                if (history.latestMessage != lastMessage) {
                    makeRequest {
                        log.info("Trying to delete message.")
                        lastMessage.delete()
                        log.info("Deleted message.")
                    }
                    this.lastMessage = makeRequest {
                        log.info("Trying to send new message.")
                        val message = channel.sendMessage(string)
                        log.info("Sent new message.")
                        message
                    }
                } else {
                    makeRequest {
                        log.info("Trying to edit message.")
                        lastMessage.edit(string)
                        log.info("Edited message.")
                    }
                }
            }
            log.info("Updated info message.")
        }

        private fun buildInfoMessage(): String {
            if (watchedGames.size > 0) {
                var message = "```Currently hosted games:\n|\n"
                var longestBotName = 0

                for ((bot, _) in watchedGames) {
                    longestBotName = if (bot.length > longestBotName) bot.length else longestBotName
                }

                for ((bot, info) in watchedGames) {
                    message += "| ${bot + " ".repeat(longestBotName - bot.length)}  ---  ${info.name}\n"
                }

                message += "```"
                return messageHeader + message
            } else {
                return messageHeader + "```There are currently no hosted games.```"
            }
        }

        private fun updateInfoMessage() {
            sendInfoMessage(buildInfoMessage())
        }

        fun processGameUpdate(info: GameInfo) {
            executor.submit {
                log.info("Processing game update: ${info.name}")
                watchedGames[info.botName] = info
                updateInfoMessage()
                log.info("Processed game update: ${info.name}")
            }
        }

        fun processGameCreate(info: GameInfo) {
            executor.submit {
                log.info("Processing game create: ${info.name}")
                watchedGames[info.botName] = info
                makeRequest { channel.sendMessage("@everyone A game has been hosted! `${info.name}`") }
                updateInfoMessage()
                log.info("Processed game create: ${info.name}")
            }
        }

        fun processGameRemove(info: GameInfo) {
            executor.submit {
                log.info("Processing game remove: ${info.name}")
                watchedGames.remove(info.botName)
                updateInfoMessage()
                log.info("Processed game remove: ${info.name}")
            }
        }

        fun kill() {
            updateTimer.cancel()
        }
    }
}