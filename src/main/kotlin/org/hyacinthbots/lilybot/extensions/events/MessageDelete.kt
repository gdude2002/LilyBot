package org.hyacinthbots.lilybot.extensions.events

import com.kotlindiscord.kord.extensions.DISCORD_PINK
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.i18n.TranslationsProvider
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.api.PKMessage
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.events.ProxiedMessageDeleteEvent
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.events.UnProxiedMessageDeleteEvent
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.message.MessageBulkDeleteEvent
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.embed
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.util.cio.toByteReadChannel
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.extensions.config.ConfigOptions
import org.hyacinthbots.lilybot.utils.DEFAULT_BUNDLE_NAME
import org.hyacinthbots.lilybot.utils.attachmentsAndProxiedMessageInfo
import org.hyacinthbots.lilybot.utils.generateBulkDeleteFile
import org.hyacinthbots.lilybot.utils.getLoggingChannelWithPerms
import org.hyacinthbots.lilybot.utils.ifNullOrEmpty
import org.hyacinthbots.lilybot.utils.requiredConfigs
import org.hyacinthbots.lilybot.utils.trimmedContents

/**
 * The class for logging deletion of messages to the guild message log.
 *
 * @since 2.0
 */
class MessageDelete : Extension() {
	override val name = "message-delete"
	override val bundle = DEFAULT_BUNDLE_NAME

	override suspend fun setup() {
		/**
		 * Logs proxied deleted messages in a guild to the message log channel designated in the config for that guild
		 * @author NoComment1105
		 * @see onMessageDelete
		 */
		event<ProxiedMessageDeleteEvent> {
			check {
				anyGuild()
				requiredConfigs(ConfigOptions.MESSAGE_DELETE_LOGGING_ENABLED, ConfigOptions.MESSAGE_LOG)
				failIf {
					event.message?.author?.id == kord.selfId
				}
			}

			action {
				onMessageDelete(translationsProvider, event.getMessageOrNull(), event.pkMessage)
			}
		}

		/**
		 * Logs unproxied deleted messages in a guild to the message log channel designated in the config for that guild.
		 * @author NoComment1105
		 * @see onMessageDelete
		 */
		event<UnProxiedMessageDeleteEvent> {
			check {
				anyGuild()
				requiredConfigs(ConfigOptions.MESSAGE_DELETE_LOGGING_ENABLED, ConfigOptions.MESSAGE_LOG)
				failIf {
					event.message?.author?.id == kord.selfId ||
						event.message?.author?.isBot == true
				}
			}

			action {
				onMessageDelete(translationsProvider, event.getMessageOrNull(), null)
			}
		}

		event<MessageBulkDeleteEvent> {
			check {
				anyGuild()
				requiredConfigs(ConfigOptions.MESSAGE_DELETE_LOGGING_ENABLED, ConfigOptions.MESSAGE_LOG)
			}

			action {
				val messageLog =
					getLoggingChannelWithPerms(ConfigOptions.MESSAGE_LOG, event.getGuildOrNull()!!) ?: return@action

				val messages = generateBulkDeleteFile(event.messages)

				messageLog.createMessage {
					bulkDeleteEmbed(event, translationsProvider, messages)
				}
			}
		}
	}

	/**
	 * Builds the embed for the bulk delete event.
	 *
	 * @param event The [MessageBulkDeleteEvent] for the event
	 * @param provider The translation provider for the extension.
	 * @param messages The messages that were deleted
	 */
	private suspend fun UserMessageCreateBuilder.bulkDeleteEmbed(
		event: MessageBulkDeleteEvent,
		provider: TranslationsProvider,
		messages: String?
	) {
		embed {
			title = provider.translate("extensions.events.messagedelete.bulk.embed.title")
			description = provider.translate("extensions.events.messagedelete.bulk.embed.description")
			field {
				name = provider.translate("extensions.events.messagedelete.bulk.embed.location")
				value = "${event.channel.mention} " +
					"(${
						event.channel.asChannelOfOrNull<GuildMessageChannel>()?.name
							?: provider.translate("extensions.events.messagedelete.noChannelName")
					})"
			}
			field {
				name = provider.translate("extensions.events.messagedelete.bulk.embed.number")
				value = event.messages.size.toString()
			}
			color = DISCORD_PINK
			timestamp = Clock.System.now()
		}
		if (messages != null) {
			addFile(
				"messages.md",
				ChannelProvider { messages.byteInputStream().toByteReadChannel() }
			)
		} else {
			content = provider.translate("extensions.events.messagedelete.bulk.embed.failedContent")
		}
	}

	/**
	 * If message logging is enabled, sends an embed describing the message deletion to the guild's message log channel.
	 *
	 * @param provider The translation provider for the extension
	 * @param message The deleted message
	 * @param proxiedMessage Extra data for PluralKit proxied messages
	 * @author trainb0y
	 */
	private suspend fun onMessageDelete(provider: TranslationsProvider, message: Message?, proxiedMessage: PKMessage?) {
		message ?: return
		val guild = message.getGuildOrNull() ?: return

		if (message.content.startsWith("pk;e", 0, true)) {
			return
		}

		val messageLog = getLoggingChannelWithPerms(ConfigOptions.MESSAGE_LOG, guild) ?: return

		messageLog.createEmbed {
			author {
				name = provider.translate("extensions.events.messagedelete.single.embed.author")
				icon = proxiedMessage?.member?.avatarUrl ?: message.author?.avatar?.cdnUrl?.toUrl()
			}
			description = provider.translate(
				"extensions.events.messageevent.location",
				DEFAULT_BUNDLE_NAME,
				arrayOf(
					message.channel.mention,
					message.channel.asChannelOfOrNull<GuildMessageChannel>()?.name
						?: provider.translate("extensions.events.messagedelete.noChannelName")
				)
			)
			color = DISCORD_PINK
			timestamp = Clock.System.now()

			field {
				name = provider.translate("extensions.events.messagedelete.single.embed.contents")
				value = message.trimmedContents()
					.ifNullOrEmpty { provider.translate("extensions.events.messageevent.failedContents") }
				inline = false
			}
			attachmentsAndProxiedMessageInfo(guild, message, proxiedMessage)
		}
	}
}
