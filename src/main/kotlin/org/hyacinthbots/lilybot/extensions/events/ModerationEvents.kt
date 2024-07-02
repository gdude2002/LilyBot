package org.hyacinthbots.lilybot.extensions.events

import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.optional.value
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.UserBehavior
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.entity.channel.Channel
import dev.kord.core.event.channel.ChannelCreateEvent
import dev.kord.core.event.channel.ChannelDeleteEvent
import dev.kord.core.event.channel.ChannelUpdateEvent
import dev.kord.core.event.channel.thread.ThreadChannelCreateEvent
import dev.kord.core.event.channel.thread.ThreadChannelDeleteEvent
import dev.kord.core.event.channel.thread.ThreadUpdateEvent
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.BanRemoveEvent
import dev.kord.core.event.guild.GuildScheduledEventCreateEvent
import dev.kord.core.event.guild.GuildScheduledEventDeleteEvent
import dev.kord.core.event.guild.GuildScheduledEventUpdateEvent
import dev.kord.core.event.guild.InviteCreateEvent
import dev.kord.core.event.guild.InviteDeleteEvent
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kord.core.event.role.RoleCreateEvent
import dev.kord.core.event.role.RoleDeleteEvent
import dev.kord.core.event.role.RoleUpdateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.database.collections.ModerationActionCollection
import org.hyacinthbots.lilybot.extensions.config.ConfigOptions
import org.hyacinthbots.lilybot.extensions.moderation.ModerationAction
import org.hyacinthbots.lilybot.utils.baseModerationEmbed
import org.hyacinthbots.lilybot.utils.dmNotificationStatusEmbedField
import org.hyacinthbots.lilybot.utils.formatPermissionSet
import org.hyacinthbots.lilybot.utils.getLoggingChannelWithPerms
import org.hyacinthbots.lilybot.utils.interval

private const val ALLOWED = "Allowed"
private const val DENIED = "Denied"

class ModerationEvents : Extension() {
	override val name: String = "moderation-events"

	private val logger = KotlinLogging.logger { "Moderation Events" }

	override suspend fun setup() {
		event<BanAddEvent> {
			check { anyGuild() }
			action {
				// If the ban doesn't exist then... ????
				event.getBanOrNull() ?: return@action
				var existingAction = ModerationActionCollection().getAction(
					ModerationAction.BAN, event.guildId, event.user.id
				)
				if (existingAction == null) {
					existingAction = ModerationActionCollection().getAction(
						ModerationAction.SOFT_BAN, event.guildId, event.user.id
					)
				}

				if (existingAction == null) {
					existingAction = ModerationActionCollection().getAction(
						ModerationAction.TEMP_BAN, event.guildId, event.user.id
					)
				}

				if (existingAction != null && existingAction.targetUserId != event.user.id) {
					// If this happens I will eat my hat
					logger.warn { "It's hat eating time from the ban command" }
					return@action
				}

				if (existingAction != null) {
					getLoggingChannelWithPerms(ConfigOptions.ACTION_LOG, event.getGuild())?.createEmbed {
						title = when (existingAction.actionType) {
							ModerationAction.BAN -> "Banned a user"
							ModerationAction.SOFT_BAN -> "Soft-banned a user"
							ModerationAction.TEMP_BAN -> "Temp-banned a user"
							else -> null // Theoretically this should never occur but the compiler cries otherwise
						}

						description = "${event.user.mention} has been ${
							if (existingAction.data.reason?.contains("quick ban", true) == false) {
								when (existingAction.actionType) {
									ModerationAction.BAN -> "banned"
									ModerationAction.SOFT_BAN -> "soft-banned"
									ModerationAction.TEMP_BAN -> "temporarily banned"
									else -> null // Again should theoretically never occur, but compiler
								}
							} else {
								when (existingAction.actionType) {
									ModerationAction.BAN -> existingAction.data.reason
									ModerationAction.SOFT_BAN -> existingAction.data.reason
									else -> null
								}
							}
						}"
						baseModerationEmbed(
							existingAction.data.reason,
							event.user,
							UserBehavior(existingAction.data.actioner!!, kord)
						)
						image = existingAction.data.imageUrl
						dmNotificationStatusEmbedField(existingAction.data.dmOutcome, existingAction.data.dmOverride)
						timestamp = Clock.System.now()
						if (existingAction.data.deletedMessages != null) {
							field {
								name = "Days of messages deleted"
								value =
									if (existingAction.actionType == ModerationAction.SOFT_BAN &&
										existingAction.data.deletedMessages == 0
									) {
										"3"
									} else {
										existingAction.data.deletedMessages.toString()
									}
								inline = false
							}
						}
						if (existingAction.data.timeData != null) {
							field {
								name = "Duration:"
								value =
									existingAction.data.timeData.durationInst?.toDiscord(TimestampType.Default) + " (${
										existingAction.data.timeData.durationDtp?.interval()
									})"
							}
						}
					}

					ModerationActionCollection().removeAction(
						existingAction.actionType,
						event.guildId,
						event.user.id
					)
				} else {
					getLoggingChannelWithPerms(ConfigOptions.ACTION_LOG, event.getGuild())?.createEmbed {
						title = "Banned a User"
						description = "${event.user.mention} has been banned!"
						baseModerationEmbed(event.getBan().reason, event.user, null)
						timestamp = Clock.System.now()
					}
				}
			}
		}
		event<BanRemoveEvent> {
			check { anyGuild() }
			action {
				val ignore = ModerationActionCollection().shouldIgnoreAction(
					ModerationAction.UNBAN,
					event.guildId,
					event.user.id
				)
				if (ignore != null && ignore) {
					return@action
				}

				val existingAction =
					ModerationActionCollection().getAction(ModerationAction.UNBAN, event.guildId, event.user.id)
				if (existingAction != null && existingAction.targetUserId != event.user.id) {
					// If this happens I will eat my hat
					logger.warn { "It's hat eating time from the unban command" }
					return@action
				}

				if (existingAction != null) {
					val isTempUnban = existingAction.data.reason?.contains("**temporary-ban**", true) == true

					getLoggingChannelWithPerms(ConfigOptions.ACTION_LOG, event.getGuild())?.createEmbed {
						title = if (isTempUnban) "Temporary ban removed" else "Unbanned a user"
						description =
							"${event.user.mention} has ${if (isTempUnban) "had their temporary ban removed!" else "been unbanned!"}\n${
								event.user.id
							}  (${event.user.username})"
						if (existingAction.data.reason?.contains("**temporary-ban-expire**") == false) {
							field {
								name = "Reason:"
								value = existingAction.data.reason.toString()
							}
						} else {
							field {
								name = "Initial Ban date:"
								value = existingAction.data.timeData?.start?.toDiscord(TimestampType.ShortDateTime)
									.toString()
							}
						}

						if (existingAction.data.actioner != null) {
							val user = UserBehavior(existingAction.data.actioner, kord).asUserOrNull()
							footer {
								text = user?.username ?: "Unable to get username"
								icon = user?.avatar?.cdnUrl?.toUrl()
							}
						}
						timestamp = Clock.System.now()
						color = DISCORD_GREEN
					}
					ModerationActionCollection().removeAction(
						existingAction.actionType,
						event.guildId,
						event.user.id
					)
				}
			}
		}
		event<ChannelCreateEvent> {
			action {
				val guild = event.channel.data.guildId.value?.let { GuildBehavior(it, event.kord) }
				val perms = formatPermissionsForDisplay(guild, event.channel)
				var allowed = perms.getValue(ALLOWED)
				var denied = perms.getValue(DENIED)

				if (allowed.isBlank()) allowed = "None overrides set"
				if (denied.isBlank()) denied = "None overrides set"

				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!)?.createEmbed {
					title = "${writeChannelType(event.channel.type)} Created"
					description = "${event.channel.mention} (${event.channel.data.name.value}) was created."
					field {
						name = "Allowed Permissions"
						value = allowed
						inline = true
					}
					field {
						name = "Denied Permissions"
						value = denied
						inline = true
					}
					timestamp = Clock.System.now()
					color = DISCORD_GREEN
				}
			}
		}
		event<ChannelDeleteEvent> {
			action {
				val guild = event.channel.data.guildId.value?.let { GuildBehavior(it, event.kord) }
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!)?.createEmbed {
					title = "${writeChannelType(event.channel.type)} Deleted"
					description = "`${event.channel.data.name.value}` was deleted."
					timestamp = Clock.System.now()
					color = DISCORD_RED
				}
			}
		}

		// TODO I'm crying please help, there are so many fields that need to be checked
		event<ChannelUpdateEvent> {
			action {
				val guild = event.channel.data.guildId.value?.let { GuildBehavior(it, event.kord) }
				val oldPerms = formatPermissionsForDisplay(guild, event.old)
				val newPerms = formatPermissionsForDisplay(guild, event.channel)
				var oldAllowed = oldPerms.getValue(ALLOWED)
				var oldDenied = oldPerms.getValue(DENIED)
				var newAllowed = newPerms.getValue(ALLOWED)
				var newDenied = newPerms.getValue(DENIED)

				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!)?.createEmbed {
					title = "${writeChannelType(event.channel.type)} Updated"
					if (event.channel.data.name != event.old?.data?.name) {
						field {
							name = "Name Change"
							value = "Old: ${event.old?.data?.name?.value}\nNew: ${event.channel.data.name.value}"
						}
					}
					if (event.channel.data.topic != event.old?.data?.topic) {
						field {
							name = "Topic changed"
							value = "Old: ${event.old?.data?.topic?.value}\nNew: ${event.channel.data.topic.value}"
						}
					}
					if (event.channel.data.nsfw != event.old?.data?.nsfw) {
						field {
							name = "NSFW Setting"
							value = event.channel.data.nsfw.discordBoolean.toString()
						}
					}
					if (event.channel.data.position != event.old?.data?.position) {
						field {
							name = "Position changed"
							value = "Old: ${event.old?.data?.position.value}\nNew: ${event.channel.data.position.value}"
						}
					}
					if (oldAllowed != newAllowed) {
						field {
							name = "New Allowed Permissions"
							value = newAllowed
							inline = true
						}
					}
					if (oldDenied != newDenied) {
						field {
							name = "Old Denied Permissions"
							value = newDenied
							inline = true
						}
					}
				}
			}
		}
		event<GuildScheduledEventCreateEvent> {
			check { anyGuild() }
			action { }
		}
		event<GuildScheduledEventDeleteEvent> {
			check { anyGuild() }
			action { }
		}
		event<GuildScheduledEventUpdateEvent> {
			check { anyGuild() }
			action { }
		}
		event<InviteCreateEvent> {
			check { anyGuild() }
			action { }
		}
		event<InviteDeleteEvent> {
			check { anyGuild() }
			action { }
		}
		event<MemberUpdateEvent> {
			check { anyGuild() }
			action { }
		}
		event<RoleCreateEvent> {
			check { anyGuild() }
			action { }
		}
		event<RoleDeleteEvent> {
			check { anyGuild() }
			action { }
		}
		event<RoleUpdateEvent> {
			check { anyGuild() }
			action { }
		}
		event<ThreadChannelCreateEvent> {
			check { anyGuild() }
			action { }
		}
		event<ThreadChannelDeleteEvent> {
			check { anyGuild() }
			action { }
		}
		event<ThreadUpdateEvent> {
			check { anyGuild() }
			action { }
		}
	}

	private fun writeChannelType(type: ChannelType): String? = when (type) {
		ChannelType.GuildCategory -> "Category"
		ChannelType.GuildNews -> "Announcement Channel"
		ChannelType.GuildForum -> "Forum Channel"
		ChannelType.GuildStageVoice -> "Stage Channel"
		ChannelType.GuildText -> "Text Channel"
		ChannelType.GuildVoice -> "Voice Channel"
		else -> null
	}

	private suspend fun formatPermissionsForDisplay(guild: GuildBehavior?, channel: Channel?): Map<String, String> {
		var map = mutableMapOf<String, String>()
		map[ALLOWED] = ""
		map[DENIED] = ""
		channel?.data?.permissionOverwrites?.value?.forEach {
			map[ALLOWED] += "${guild!!.getRoleOrNull(it.id)?.mention}: ${formatPermissionSet(it.allow)}\n"
			map[DENIED] += "${guild.getRoleOrNull(it.id)?.mention}: ${formatPermissionSet(it.deny)}\n"
		}
		return map
	}
}
