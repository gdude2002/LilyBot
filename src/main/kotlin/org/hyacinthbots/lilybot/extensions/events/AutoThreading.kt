package org.hyacinthbots.lilybot.extensions.events

import com.kotlindiscord.kord.extensions.DISCORD_BLACK
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.channel
import com.kotlindiscord.kord.extensions.commands.converters.impl.defaultingBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalRole
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.i18n.TranslationsProvider
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.api.PKMessage
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.events.PKMessageCreateEvent
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.events.ProxiedMessageCreateEvent
import com.kotlindiscord.kord.extensions.modules.extra.pluralkit.events.UnProxiedMessageCreateEvent
import com.kotlindiscord.kord.extensions.modules.unsafe.annotations.UnsafeAPI
import com.kotlindiscord.kord.extensions.modules.unsafe.extensions.unsafeSubCommand
import com.kotlindiscord.kord.extensions.modules.unsafe.types.InitialSlashCommandResponse
import com.kotlindiscord.kord.extensions.modules.unsafe.types.ackEphemeral
import com.kotlindiscord.kord.extensions.modules.unsafe.types.respondEphemeral
import com.kotlindiscord.kord.extensions.utils.delete
import com.kotlindiscord.kord.extensions.utils.respond
import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.threads.edit
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.entity.Message
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.thread.TextChannelThread
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.event.channel.thread.ThreadChannelCreateEvent
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.rest.builder.message.embed
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.database.collections.AutoThreadingCollection
import org.hyacinthbots.lilybot.database.collections.ModerationConfigCollection
import org.hyacinthbots.lilybot.database.collections.ThreadsCollection
import org.hyacinthbots.lilybot.database.entities.AutoThreadingData
import org.hyacinthbots.lilybot.extensions.config.ConfigOptions
import org.hyacinthbots.lilybot.utils.DEFAULT_BUNDLE_NAME
import org.hyacinthbots.lilybot.utils.botHasChannelPerms
import org.hyacinthbots.lilybot.utils.canPingRole
import org.hyacinthbots.lilybot.utils.getLoggingChannelWithPerms

class AutoThreading : Extension() {
	override val name = "auto-threading"
	override val bundle = DEFAULT_BUNDLE_NAME

	override suspend fun setup() {
		ephemeralSlashCommand {
			name = "extensions.events.autothreading.autothreading.name"
			description = "extensions.events.autothreading.autothreading.description"

			@OptIn(UnsafeAPI::class)
			unsafeSubCommand(::AutoThreadingArgs) {
				name = "extensions.events.autothreading.autothreading.enable.name"
				description = "extensions.events.autothreading.autothreading.enable.description"

				initialResponse = InitialSlashCommandResponse.None

				requirePermission(Permission.ManageChannels)

				check {
					anyGuild()
					hasPermission(Permission.ManageChannels)
					requireBotPermissions(Permission.SendMessages)
					botHasChannelPerms(Permissions(Permission.SendMessages))
				}

				action {
					// Check if the auto-threading is disabled
					if (AutoThreadingCollection().getSingleAutoThread(channel.id) != null) {
						ackEphemeral()
						respondEphemeral {
							content = translate("extensions.events.autothreading.autothreading.alreadyOn")
						}
						return@action
					}

					// Check if the role can be pinged
					if (!canPingRole(arguments.role, guild!!.id, this@unsafeSubCommand.kord)) {
						ackEphemeral()
						respondEphemeral {
							content = translate("extensions.events.autothreading.autothreading.enable.noMention")
						}
						return@action
					}

					var message: String? = null

					if (arguments.message) {
						val modalObj = MessageModal()

						this@unsafeSubCommand.componentRegistry.register(modalObj)

						event.interaction.modal(
							modalObj.title,
							modalObj.id
						) {
							modalObj.applyToBuilder(this, getLocale(), null)
						}

						modalObj.awaitCompletion { modalSubmitInteraction ->
							interactionResponse = modalSubmitInteraction?.deferEphemeralMessageUpdate()
						}

						message = modalObj.msgInput.value!!
					} else {
						ackEphemeral()
					}

					respondEphemeral {
						content = translate("extensions.events.autothreading.autothreading.enable.enabled")
					}

					// Add the channel to the database as auto-threaded
					AutoThreadingCollection().setAutoThread(
						AutoThreadingData(
							guildId = guild!!.id,
							channelId = channel.id,
							roleId = arguments.role?.id,
							preventDuplicates = arguments.preventDuplicates,
							archive = arguments.archive,
							contentAwareNaming = arguments.contentAwareNaming,
							mention = arguments.mention,
							creationMessage = message,
							addModsAndRole = arguments.addModsAndRole
						)
					)

					// Log the change
					val utilityLog = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!) ?: return@action

					utilityLog.createEmbed {
						title = translate("extensions.events.autothreading.autothreading.enable.embed.title")
						description = null
						field {
							name = translate("extensions.events.autothreading.autothreading.enable.embed.channel")
							value = channel.mention
							inline = true
						}
						field {
							name = translate("extensions.events.autothreading.autothreading.enable.embed.role")
							value = arguments.role?.mention ?: "null"
							inline = true
						}
						field {
							name =
								translate("extensions.events.autothreading.autothreading.enable.embed.preventDuplicates")
							value = arguments.preventDuplicates.toString()
							inline = true
						}
						field {
							name = translate("extensions.events.autothreading.autothreading.enable.embed.beginArchived")
							value = arguments.archive.toString()
							inline = true
						}
						field {
							name = translate("extensions.events.autothreading.autothreading.enable.embed.smartNaming")
							value = arguments.contentAwareNaming.toString()
							inline = true
						}
						field {
							name = translate("extensions.events.autothreading.autothreading.enable.embed.mention")
							value = arguments.mention.toString()
							inline = true
						}
						field {
							name =
								translate("extensions.events.autothreading.autothreading.enable.embed.initialMessage")
							value = if (message != null) "```$message```" else "null"
							inline = message == null
						}
						footer {
							text = user.asUser().username
							icon = user.asUser().avatar?.cdnUrl?.toUrl()
						}
						timestamp = Clock.System.now()
						color = DISCORD_BLACK
					}
				}
			}

			ephemeralSubCommand {
				name = "extensions.events.autothreading.autothreading.disable.name"
				description = "extensions.events.autothreading.autothreading.disable.description"

				requirePermission(Permission.ManageChannels)

				check {
					anyGuild()
					hasPermission(Permission.ManageChannels)
					requireBotPermissions(Permission.SendMessages)
					botHasChannelPerms(Permissions(Permission.SendMessages))
				}

				action {
					// Check if auto-threading is enabled
					if (AutoThreadingCollection().getSingleAutoThread(channel.id) == null) {
						respond {
							content = translate("extensions.events.autothreading.autothreading.alreadyOff")
						}
						return@action
					}

					// Remove the channel from the database as auto-threaded
					AutoThreadingCollection().deleteAutoThread(channel.id)

					// Respond to the user
					respond {
						content = translate("extensions.events.autothreading.autothreading.disable.disabled")
					}

					// Log the change
					val utilityLog = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!) ?: return@action

					utilityLog.createEmbed {
						title = translate("extensions.events.autothreading.autothreading.disable.embed.title")
						description = null

						field {
							name = translate("extensions.events.autothreading.autothreading.disable.embed.channel")
							value = channel.mention
							inline = true
						}
						footer {
							text = user.asUser().username
							icon = user.asUser().avatar?.cdnUrl?.toUrl()
						}
						timestamp = Clock.System.now()
						color = DISCORD_BLACK
					}
				}
			}

			ephemeralSubCommand {
				name = "extensions.events.autothreading.autothreading.list.name"
				description = "extensions.events.autothreading.autothreading.list.description"

				check {
					anyGuild()
					requireBotPermissions(Permission.SendMessages)
					botHasChannelPerms(Permissions(Permission.SendMessages))
				}

				action {
					val autoThreads = AutoThreadingCollection().getAllAutoThreads(guild!!.id)
					var responseContent: String? = null
					autoThreads.forEach {
						responseContent += "\n<#${it.channelId}>"
						if (responseContent!!.length > 4080) {
							responseContent += "(List trimmed.)"
							return@forEach
						}
					}

					respond {
						embed {
							if (responseContent == null) {
								title = translate("extensions.events.autothreading.autothreading.list.noChannels")
								description =
									translate("extensions.events.autothreading.autothreading.list.addNewChannels")
							} else {
								title = translate("extensions.events.autothreading.autothreading.list.channelList")
								description = responseContent.replace("null", "")
							}
						}
					}
				}
			}

			ephemeralSubCommand(::AutoThreadingViewArgs) {
				name = "extensions.events.autothreading.autothreading.view.name"
				description = "extensions.events.autothreading.autothreading.view.description"

				requirePermission(Permission.ManageChannels)

				check {
					anyGuild()
					requirePermission(Permission.ManageChannels)
					requireBotPermissions(Permission.SendMessages)
					botHasChannelPerms(Permissions(Permission.SendMessages))
				}

				action {
					val autoThread = AutoThreadingCollection().getSingleAutoThread(arguments.channel.id)
					if (autoThread == null) {
						respond {
							content = translate("extensions.events.autothreading.autothreading.view.noThreaded")
						}
						return@action
					}

					respond {
						embed {
							title = translate("extensions.events.autothreading.autothreading.view.embed.title")
							description = translate(
								"extensions.events.autothreading.autothreading.view.embed.description",
								arrayOf(arguments.channel.mention)
							)
							field {
								name = translate("extensions.events.autothreading.autothreading.view.embed.role")
								value = if (autoThread.roleId != null) {
									guild!!.getRoleOrNull(autoThread.roleId)?.mention
										?: translate("extensions.events.autothreading.autothreading.view.embed.unable")
								} else {
									translate("basic.none")
								}
							}
							field {
								name =
									translate("extensions.events.autothreading.autothreading.view.embed.preventDuplicates")
								value = autoThread.preventDuplicates.toString()
							}
							field {
								name =
									translate("extensions.events.autothreading.autothreading.view.embed.archiveOnStart")
								value = autoThread.archive.toString()
							}
							field {
								name =
									translate("extensions.events.autothreading.autothreading.view.embed.contentAwareNaming")
								value = autoThread.contentAwareNaming.toString()
							}
							field {
								name =
									translate("extensions.events.autothreading.autothreading.view.embed.mentionCreator")
								value = autoThread.mention.toString()
							}
							field {
								name =
									translate("extensions.events.autothreading.autothreading.view.embed.creationMessage")
								value = autoThread.creationMessage ?: translate("basic.default")
							}
							field {
								name = translate("extensions.events.autothreading.autothreading.view.embed.addMods")
								value = autoThread.addModsAndRole.toString()
							}
						}
					}
				}
			}
		}

		event<ProxiedMessageCreateEvent> {
			check {
				anyGuild()
				failIf {
					event.pkMessage.sender == kord.selfId ||
						listOf(
							MessageType.ChatInputCommand,
							MessageType.ThreadCreated,
							MessageType.ThreadStarterMessage
						).contains(event.message.type) ||
						listOf(
							ChannelType.GuildNews,
							ChannelType.GuildVoice,
							ChannelType.PublicGuildThread,
							ChannelType.PublicNewsThread
						).contains(event.message.getChannelOrNull()?.type)
				}
			}

			action {
				onMessageSend(event, getTranslationProvider(), event.getMessageOrNull(), event.pkMessage)
			}
		}

		event<UnProxiedMessageCreateEvent> {
			check {
				anyGuild()
				failIf {
					event.message.author?.id == kord.selfId ||
						listOf(
							MessageType.ChatInputCommand,
							MessageType.ThreadCreated,
							MessageType.ThreadStarterMessage
						).contains(event.message.type) ||
						listOf(
							ChannelType.GuildNews,
							ChannelType.GuildVoice,
							ChannelType.PublicGuildThread,
							ChannelType.PublicNewsThread
						).contains(event.message.getChannelOrNull()?.type)
				}
			}

			action {
				onMessageSend(event, getTranslationProvider(), event.getMessageOrNull())
			}
		}

		event<ThreadChannelCreateEvent> {
			check {
				anyGuild()
				failIf {
					event.channel.ownerId == kord.selfId ||
						event.channel.member != null
				}
			}

			action {
				// fixme this event fires twice for some unknown reason so this is a workaround
				delay(1000)
				if (event.channel.getLastMessage()?.withStrategy(EntitySupplyStrategy.rest) != null) return@action

				val thread = event.channel.asChannelOfOrNull<TextChannelThread>() ?: return@action
				val options = AutoThreadingCollection().getSingleAutoThread(thread.parentId) ?: return@action

				handleThreadCreation(
					options,
					thread,
					event.channel.owner.asUser()
				)
			}
		}
	}

	inner class AutoThreadingArgs : Arguments() {
		val role by optionalRole {
			name = "extensions.events.autothreading.autothreading.arguments.role.name"
			description = "extensions.events.autothreading.autothreading.arguments.role.description"
		}

		val addModsAndRole by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.addMods.name"
			description = "extensions.events.autothreading.autothreading.arguments.addMods.description"
			defaultValue = false
		}

		val preventDuplicates by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.preventDuplicates.name"
			description = "extensions.events.autothreading.autothreading.arguments.preventDuplicates.description"
			defaultValue = false
		}

		val archive by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.archive.name"
			description = "extensions.events.autothreading.autothreading.arguments.archive.description"
			defaultValue = false
		}

		val contentAwareNaming by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.contentAware.name"
			description = "extensions.events.autothreading.autothreading.arguments.contentAware.description"
			defaultValue = false
		}

		val mention by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.mention.name"
			description = "extensions.events.autothreading.autothreading.arguments.mention.description"
			defaultValue = false
		}

		val message by defaultingBoolean {
			name = "extensions.events.autothreading.autothreading.arguments.message.name"
			description = "extensions.events.autothreading.autothreading.arguments.message.description"
			defaultValue = false
		}
	}

	inner class AutoThreadingViewArgs : Arguments() {
		val channel by channel {
			name = "extensions.events.autothreading.autothreading.arguments.channel.name"
			description = "extensions.events.autothreading.autothreading.arguments.channel.description"
		}
	}

	inner class MessageModal : ModalForm() {
		override var title = "extensions.events.autothreading.autothreading.modal.title"

		val msgInput = paragraphText {
			label = "extensions.events.autothreading.autothreading.modal.msgInput.name"
			placeholder = "extensions.events.autothreading.autothreading.modal.msgInput.placeholder"
			required = true
		}
	}

	/**
	 * A single function for both Proxied and Non-Proxied message to be turned into threads.
	 *
	 * @param event The event for the message creation
	 * @param message The original message that wasn't proxied
	 * @param proxiedMessage The proxied message, if the message was proxied
	 * @since 4.6.0
	 * @author NoComment1105
	 */
	private suspend fun <T : PKMessageCreateEvent> onMessageSend(
		event: T,
		provider: TranslationsProvider,
		message: Message?,
		proxiedMessage: PKMessage? = null
	) {
		val memberFromPk = if (proxiedMessage != null) event.getGuild().getMemberOrNull(proxiedMessage.sender) else null

		val channel: TextChannel = if (proxiedMessage == null) {
			message?.channel?.asChannelOfOrNull() ?: return
		} else {
			// Check the real message member too, despite the pk message not being null, we may still be able to use the original
			message?.channel?.asChannelOfOrNull()
				?: try {
					event.getGuild().getChannelOfOrNull(proxiedMessage.channel)
				} catch (_: IllegalArgumentException) {
					null
				} ?: return
		}

		val authorId: Snowflake = if (proxiedMessage == null) {
			message?.author?.id ?: return
		} else {
			message?.author?.id ?: proxiedMessage.sender
		}

		val options = AutoThreadingCollection().getSingleAutoThread(channel.id) ?: return

		var threadName: String? = event.message.content.trim().split("\n").firstOrNull()?.take(75)

		if (!options.contentAwareNaming || threadName == null) {
			threadName = "${provider.translate("extensions.events.autothreading.autothreading.threadFor")} ${
				message?.author?.asUser()?.username ?: proxiedMessage?.member?.name
			}".take(75)
		}

		if (options.preventDuplicates) {
			var previousThreadExists = false
			var previousUserThread: ThreadChannel? = null
			val ownerThreads = ThreadsCollection().getOwnerThreads(authorId)

			ownerThreads.forEach {
				val thread = try {
					event.guild?.getChannelOfOrNull<ThreadChannel>(it.threadId)
				} catch (_: IllegalArgumentException) {
					null
				}
				if (thread == null) {
					ThreadsCollection().removeThread(it.threadId)
				} else if (thread.parentId == channel.id && !thread.isArchived) {
					previousThreadExists = true
					previousUserThread = thread
				}
			}

			if (previousThreadExists) {
				val response = event.message.respond {
					// There is a not-null call because the compiler knows it's not null if the boolean is true.
					content = provider.translate(
						"extensions.events.autothreading.autothreading.existingThread",
						DEFAULT_BUNDLE_NAME,
						arrayOf(previousUserThread!!.mention)
					)
				}
				event.message.delete("User already has a thread")
				response.delete(10000L, false)
				return
			}
		}

		val thread = channel.startPublicThreadWithMessage(
			message?.id ?: proxiedMessage!!.channel,
			threadName
		) {
			autoArchiveDuration = channel.data.defaultAutoArchiveDuration.value ?: ArchiveDuration.Day
		}

		ThreadsCollection().setThreadOwner(event.getGuild().id, thread.id, event.member!!.id, channel.id)

		handleThreadCreation(
			options,
			thread,
			message?.author ?: memberFromPk!!.asUser()
		)
	}

	private suspend inline fun handleThreadCreation(
		inputOptions: AutoThreadingData,
		inputThread: TextChannelThread,
		inputUser: User
	) {
		val threadMessage = inputThread.createMessage(if (inputOptions.mention) inputUser.mention else "Placeholder")

		if (inputOptions.roleId != null) {
			val role = inputThread.guild.getRole(inputOptions.roleId)
			val moderatorRoleId = ModerationConfigCollection().getConfig(inputThread.guildId)?.role
			var moderatorRole: Role? = null
			if (moderatorRoleId != null) {
				moderatorRole = inputThread.guild.getRole(moderatorRoleId)
			}

			if (moderatorRole != null && moderatorRole.mentionable && inputOptions.addModsAndRole) {
				threadMessage.edit {
					content = role.mention + moderatorRole.mention
				}
			} else {
				threadMessage.edit {
					content = role.mention
				}
			}
		}

		if (inputOptions.creationMessage != null) {
			threadMessage.edit {
				content =
					if (inputOptions.mention) {
						inputUser.mention + " " + inputOptions.creationMessage
					} else {
						inputOptions.creationMessage
					}
			}
		} else {
			threadMessage.delete()
		}

		if (inputOptions.archive) {
			inputThread.edit {
				archived = true
				reason = "Initial thread creation"
			}
		}
	}
}
