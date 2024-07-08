package org.hyacinthbots.lilybot.extensions.events

import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.DISCORD_YELLOW
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.time.TimestampType
import com.kotlindiscord.kord.extensions.time.toDiscord
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.ForumTag
import dev.kord.common.entity.GuildScheduledEventPrivacyLevel
import dev.kord.common.entity.optional.value
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.UserBehavior
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.Category
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
import dev.kord.core.event.guild.GuildScheduledEventEvent
import dev.kord.core.event.guild.GuildScheduledEventUpdateEvent
import dev.kord.core.event.guild.InviteCreateEvent
import dev.kord.core.event.guild.InviteDeleteEvent
import dev.kord.core.event.guild.MemberUpdateEvent
import dev.kord.core.event.role.RoleCreateEvent
import dev.kord.core.event.role.RoleDeleteEvent
import dev.kord.core.event.role.RoleUpdateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.embed
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.database.collections.ModerationActionCollection
import org.hyacinthbots.lilybot.extensions.config.ConfigOptions
import org.hyacinthbots.lilybot.extensions.moderation.ModerationAction
import org.hyacinthbots.lilybot.utils.afterDot
import org.hyacinthbots.lilybot.utils.baseModerationEmbed
import org.hyacinthbots.lilybot.utils.dmNotificationStatusEmbedField
import org.hyacinthbots.lilybot.utils.formatPermissionSet
import org.hyacinthbots.lilybot.utils.getLoggingChannelWithPerms
import org.hyacinthbots.lilybot.utils.ifNullOrEmpty
import org.hyacinthbots.lilybot.utils.interval

/** A String identifier to use for the permission map to get allowed permissions. */
private const val ALLOWED = "Allowed"

/** A String identifier to use for the permission map to get denied permissions. */
private const val DENIED = "Denied"

class ModerationEvents : Extension() {
	override val name: String = "moderation-events"

	private val logger = KotlinLogging.logger("Moderation Events")

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
									existingAction.data.timeData!!.durationInst?.toDiscord(TimestampType.Default) + " (${
										existingAction.data.timeData!!.durationDtp?.interval()
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

		event<ChannelUpdateEvent> {
			action {
				val guild = event.channel.data.guildId.value?.let { GuildBehavior(it, event.kord) }
				val oldPerms = formatPermissionsForDisplay(guild, event.old)
				val newPerms = formatPermissionsForDisplay(guild, event.channel)
				val oldAllowed = oldPerms.getValue(ALLOWED)
				val oldDenied = oldPerms.getValue(DENIED)
				val newAllowed = newPerms.getValue(ALLOWED)
				val newDenied = newPerms.getValue(DENIED)

				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!)?.createEmbed {
					title = "${writeChannelType(event.channel.type)} Updated"
					if (event.channel.type != event.old?.type) {
						field {
							name = "Type change"
							value =
								"Old: ${writeChannelType(event.old?.type)}\nNew: ${writeChannelType(event.channel.type)}"
						}
					}
					if (event.channel.data.name != event.old?.data?.name) {
						field {
							name = "Name Change"
							value = "Old: ${event.old?.data?.name?.value}\nNew: ${event.channel.data.name.value}"
						}
					}
					if (event.channel.data.topic.value != event.old?.data?.topic?.value) {
						field {
							name = "Topic changed"
							value = "Old: ${event.old?.data?.topic?.value}\nNew: ${event.channel.data.topic.value}"
						}
					}
					if (event.channel.data.parentId != event.old?.data?.parentId) {
						field {
							name = "Parent Category Changed"
							value =
								"Old: ${kord.getChannelOf<Category>(event.old?.data?.parentId.value!!)?.mention}\n" +
									"New: ${kord.getChannelOf<Category>(event.channel.data.parentId.value!!)?.mention}"
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
					if (event.channel.data.rateLimitPerUser != event.old?.data?.rateLimitPerUser) {
						field {
							name = "Slowmode time changed"
							value = "Old: ${event.old?.data?.rateLimitPerUser?.value ?: "0"}\n" +
								"New: ${event.channel.data.rateLimitPerUser.value ?: "0"}"
						}
					}
					if (event.channel.data.bitrate != event.old?.data?.bitrate) {
						field {
							name = "Bitrate changed"
							value = "Old: ${event.old?.data?.bitrate.value}\nNew: ${event.channel.data.bitrate.value}"
						}
					}
					if (event.channel.data.userLimit != event.old?.data?.userLimit) {
						field {
							name = "User limit changed"
							value = "Old: ${event.old?.data?.userLimit.value ?: "0"}\n" +
								"New: ${event.channel.data.userLimit.value ?: "0"}"
						}
					}
					if (event.channel.data.rtcRegion != event.old?.data?.rtcRegion) {
						field {
							name = "Region changed"
							value = "Old: ${event.old?.data?.rtcRegion?.value ?: "Automatic"}\n" +
								"New: ${event.channel.data.rtcRegion.value ?: "Automatic"}"
						}
					}
					if (event.channel.data.videoQualityMode != event.old?.data?.videoQualityMode) {
						field {
							name = "Video Quality Changed"
							value = "Old: ${event.old?.data?.videoQualityMode?.value.afterDot()}\n" +
								"New: ${event.channel.data.videoQualityMode.value.afterDot()}"
						}
					}
					if (event.channel.data.defaultAutoArchiveDuration != event.old?.data?.defaultAutoArchiveDuration) {
						field {
							name = "Default Auto-Archive Duration"
							value = "Old: ${event.old?.data?.defaultAutoArchiveDuration?.value?.duration}\n" +
								"New: ${event.channel.data.defaultAutoArchiveDuration.value?.duration}"
						}
					}
					if (event.channel.data.defaultSortOrder != event.old?.data?.defaultSortOrder) {
						field {
							name = "Default Sort Changed"
							value = "Old: ${event.old?.data?.defaultSortOrder?.value.afterDot()}\n" +
								"New: ${event.channel.data.defaultSortOrder.value.afterDot()}"
						}
					}
					if (event.channel.data.defaultForumLayout != event.old?.data?.defaultForumLayout) {
						field {
							name = "Default Layout Changed"
							value = "Old: ${event.old?.data?.defaultForumLayout?.value.afterDot()}\n" +
								"New: ${event.channel.data.defaultForumLayout.value.afterDot()}"
						}
					}
					if (event.channel.data.availableTags != event.old?.data?.availableTags) {
						field {
							name = "Available Tags Changed"
							value = "Old: ${formatAvailableTags(event.old?.data?.availableTags?.value)}\n" +
								"New: ${formatAvailableTags(event.channel.data.availableTags.value)}"
						}
					}
					if (event.channel.data.appliedTags != event.old?.data?.appliedTags) {
						field {
							name = "Applied Tags Changed"
							value = "Old: ${event.old?.data?.appliedTags?.value}\n" +
								"New: ${event.channel.data.appliedTags.value}"
						}
					}
					if (event.channel.data.defaultReactionEmoji != event.old?.data?.defaultReactionEmoji) {
						field {
							name = "Default Reaction Emoji Changed"
							value = "Old: ${event.old?.data?.defaultReactionEmoji?.value?.emojiName}\n" +
								"New: ${event.channel.data.defaultReactionEmoji.value?.emojiName}"
						}
					}
					if (event.channel.data.defaultThreadRateLimitPerUser != event.old?.data?.defaultThreadRateLimitPerUser) {
						field {
							name = "Default Thread Slowmode Changed"
							value = "Old: ${event.old?.data?.defaultThreadRateLimitPerUser?.value}\n" +
								"New: ${event.channel.data.defaultThreadRateLimitPerUser.value}"
						}
					}
					if (oldAllowed != newAllowed) {
						field {
							name = "New Allowed Permissions"
							value = newAllowed
							inline = true
						}
						field {
							name = "Old Allowed Permissions"
							value = oldAllowed
							inline = true
						}
					}
					if (oldDenied != newDenied) {
						field {
							name = "New Denied Permissions"
							value = newDenied
							inline = false
						}
						field {
							name = "Old Denied Permissions"
							value = oldDenied
							inline = true
						}
					}
					color = DISCORD_YELLOW
					timestamp = Clock.System.now()
				}
			}
		}
		event<GuildScheduledEventCreateEvent> {
			check { anyGuild() }
			action {
				val guild = GuildBehavior(event.guildId, kord)
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Scheduled Event Created!"
					description = "An event has been created!"
					guildEventEmbed(event, guild)
					// This appears to exist in the front end but the api doesn't have it anywhere, api payloads contain
					// a recurrence field that would fill this but the api doesn't mention it
// 					field {
// 						name = "Repeating Frequency"
// 						value = event.scheduledEvent.
// 					}
					field {
						name = "Guild Members only"
						value = if (event.scheduledEvent.privacyLevel == GuildScheduledEventPrivacyLevel.GuildOnly) {
							"true"
						} else {
							"false"
						}
					}
					color = DISCORD_GREEN
					footer {
						text =
							"Created by ${event.scheduledEvent.creatorId?.let { guild.getMemberOrNull(it) }?.username}"
						icon =
							event.scheduledEvent.creatorId?.let { guild.getMemberOrNull(it) }?.avatar?.cdnUrl?.toUrl()
					}
				}
			}
		}
		event<GuildScheduledEventDeleteEvent> {
			check { anyGuild() }
			action {
				val guild = GuildBehavior(event.guildId, kord)
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Scheduled Event Deleted!"
					description = "An event has been deleted"
					guildEventEmbed(event, guild)
					color = DISCORD_RED
					footer {
						text =
							"Originally created by ${event.scheduledEvent.creatorId?.let { guild.getMemberOrNull(it) }?.username}"
						icon =
							event.scheduledEvent.creatorId?.let { guild.getMemberOrNull(it) }?.avatar?.cdnUrl?.toUrl()
					}
				}
			}
		}
		event<GuildScheduledEventUpdateEvent> {
			check { anyGuild() }
			action {
				val guild = GuildBehavior(event.guildId, kord)
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Scheduled Event Updated!"
					description = "An event has been updated"
					if (event.scheduledEvent.name != event.oldEvent?.name) {
						field {
							name = "Name changed"
							value = "Old: ${event.oldEvent?.name}\nNew: ${event.scheduledEvent.name}"
						}
					}
					if (event.scheduledEvent.description != event.oldEvent?.description) {
						field {
							name = "Description changed"
							value = "Old: ${event.oldEvent?.description}\nNew: ${event.scheduledEvent.description}"
						}
					}
					if (event.scheduledEvent.channelId != event.oldEvent?.channelId) {
						field {
							name = "Location changed"
							value =
								"Old: ${
									event.oldEvent?.channelId?.let { guild.getChannelOrNull(it) }?.mention
										?: "Unable to get channel"
								}\nNew: ${
									event.scheduledEvent.channelId?.let { guild.getChannelOrNull(it) }?.mention
										?: "Unable to get channel"
								}"
						}
					}
					if (event.scheduledEvent.scheduledStartTime != event.oldEvent?.scheduledStartTime) {
						field {
							name = "Start time changed"
							value =
								"Old: ${event.oldEvent?.scheduledStartTime?.toDiscord(TimestampType.ShortDateTime)}" +
									"\nNew: ${event.scheduledEvent.scheduledStartTime.toDiscord(TimestampType.ShortDateTime)}"
						}
					}
					color = DISCORD_YELLOW
				}
			}
		}
		event<InviteCreateEvent> {
			check { anyGuild() }
			action {
				val guild = event.guildId?.let { GuildBehavior(it, kord) } ?: return@action
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Invite link created"
					description = "An invite has been created"
					field {
						name = "Code"
						value = event.code
					}
					field {
						name = "Target Channel"
						value = event.channel.mention
					}
					field {
						name = "Max uses"
						value = event.maxUses.toString()
					}
					field {
						name = "Duration of Invite"
						value = event.maxAge.toIsoString()
					}
					field {
						name = "Temporary Membership invite"
						value = event.isTemporary.toString()
					}
					footer {
						text = "Created by ${event.getInviterAsMemberOrNull()?.mention}"
						icon = event.getInviterAsMemberOrNull()?.avatar?.cdnUrl?.toUrl()
					}
					timestamp = Clock.System.now()
					color = DISCORD_GREEN
				}
			}
		}
		event<InviteDeleteEvent> {
			check { anyGuild() }
			action {
				val guild = event.guildId?.let { GuildBehavior(it, kord) } ?: return@action
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Invite link deleted"
					description = "An invite has been deleted"
					field {
						name = "Code"
						value = event.code
					}
					field {
						name = "Target Channel"
						value = event.channel.mention
					}
					timestamp = Clock.System.now()
					color = DISCORD_RED
				}
			}
		}
		event<MemberUpdateEvent> {
			check { anyGuild() }
			action { }
		}
		event<RoleCreateEvent> {
			action {
				val guild = GuildBehavior(event.guildId, kord)
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Created a Role"
					description = "A new role has been created"
					field {
						name = "Role name"
						value = event.role.name
						inline = true
					}
					field {
						name = "Role Color"
						value = event.role.color.rgb.toString()
						inline = true
					}
					field {
						name = "Position"
						value = event.role.rawPosition.toString()
						inline = true
					}
					field {
						name = "Display separately?"
						value = event.role.hoisted.toString()
						inline = true
					}
					field {
						name = "Mentionable"
						value = event.role.mentionable.toString()
						inline = true
					}
					field {
						name = "Icon"
						value = event.role.icon?.cdnUrl?.toUrl() ?: "No icon"
						inline = true
					}
					field {
						name = "Emoji"
						value = event.role.unicodeEmoji ?: "No emoji"
						inline = true
					}
					field {
						name = "Managed by integration?"
						value = event.role.managed.toString()
						inline = true
					}
					field {
						name = "Permissions"
						value = formatPermissionSet(event.role.permissions)
					}
					color = DISCORD_GREEN
					timestamp = Clock.System.now()
				}
			}
		}
		event<RoleDeleteEvent> {
			action {
				val guild = GuildBehavior(event.guildId, kord)
				getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)?.createEmbed {
					title = "Role deleted"
					description = "A role has been deleted"
					field {
						name = "Role name"
						value = event.role?.name ?: "Unable to get name"
					}
					color = DISCORD_RED
					timestamp = Clock.System.now()
				}
			}
		}
		event<RoleUpdateEvent> {
			action {
				val guild = GuildBehavior(event.guildId, kord)
				val channel = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild)
				channel?.createMessage {
					embed {
						title = "Updated a Role"
						description = "A role has been updated"
						if (event.old?.name != event.role.name) {
							field {
								name = "Name changed"
								value = "Old: ${event.old?.name}\nNew: ${event.role.name}"
							}
						}
						if (event.old?.hoisted != event.role.hoisted) {
							field {
								name = "Display separately setting changed"
								value = "Old: ${event.old?.hoisted}\nNew: ${event.role.hoisted}"
							}
						}
						if (event.old?.mentionable != event.role.mentionable) {
							field {
								name = "Mentionable setting changed"
								value = "Old: ${event.old?.mentionable}\nNew: ${event.role.mentionable}"
							}
						}
						if (event.old?.getPosition() != event.role.getPosition()) {
							field {
								name = "Position changed"
								value = "Old: ${event.old?.getPosition()}\nNew: ${event.role.getPosition()}"
							}
						}
						if (event.old?.icon != event.role.icon) {
							field {
								name = "Icon changed"
								value = "Old: ${event.old?.icon?.cdnUrl?.toUrl() ?: "No icon"}\n" +
									"New: ${event.role.icon?.cdnUrl?.toUrl() ?: "No icon"}"
							}
						}
						if (event.old?.unicodeEmoji != event.role.unicodeEmoji) {
							field {
								name = "Emoji changed"
								value =
									"Old: ${event.old?.unicodeEmoji ?: "No emoji"}\nNew:${event.role.unicodeEmoji ?: "No emoji"}"
							}
						}
						if (event.old?.permissions != event.role.permissions) {
							field {
								name = "Permissions changed"
								value = "Old: ${
									event.old?.permissions?.let { formatPermissionSet(it) } ?: "Unable to get permissions"
								}\nNew: ${formatPermissionSet(event.role.permissions)}"
							}
						}
						color = DISCORD_GREEN
						timestamp = Clock.System.now()
					}
					if (event.old?.color != event.role.color) {
						embed {
							description = "Old color"
							color = if (event.old?.color?.rgb != 0) event.old?.color else null
						}
						embed {
							description = "New color"
							color = event.role.color
						}
					}
				}
			}
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

	/**
	 * Writes a [ChannelType] into a String to use as a reasonable title.
	 *
	 * @param type The type of the channel
	 * @return A String for the channel title
	 * @author NoComment1105
	 * @since 5.0.0
	 */
	private fun writeChannelType(type: ChannelType?): String? = when (type) {
		ChannelType.GuildCategory -> "Category"
		ChannelType.GuildNews -> "Announcement Channel"
		ChannelType.GuildForum -> "Forum Channel"
		ChannelType.GuildStageVoice -> "Stage Channel"
		ChannelType.GuildText -> "Text Channel"
		ChannelType.GuildVoice -> "Voice Channel"
		else -> null
	}

	/**
	 * Formats the permission overwrites for a [channel] to a string map of allowed and denied permissions.
	 *
	 * @param guild The guild the channel is in
	 * @param channel The channel object to get the permissions for
	 * @return A [Map] of strings for allowed and denied permissions
	 * @author NoComment1105
	 * @since 5.0.0
	 */
	private suspend inline fun formatPermissionsForDisplay(
		guild: GuildBehavior?,
		channel: Channel?
	): Map<String, String> {
		val map = mutableMapOf<String, String>()
		map[ALLOWED] = ""
		map[DENIED] = ""
		channel?.data?.permissionOverwrites?.value?.forEach {
			map[ALLOWED] += "${guild!!.getRoleOrNull(it.id)?.mention}: ${formatPermissionSet(it.allow)}\n"
			map[DENIED] += "${guild.getRoleOrNull(it.id)?.mention}: ${formatPermissionSet(it.deny)}\n"
		}
		return map
	}

	/**
	 * Formats the Available tags ([ForumTag]) into a readable bullet pointed display for the update embed.
	 *
	 * @param tagList A List of [ForumTag]s to format
	 * @return The formated string from [tagList]
	 * @author NoComment1105
	 * @since 5.0.0
	 */
	private fun formatAvailableTags(tagList: List<ForumTag>?): String {
		var tagString = ""
		tagList?.forEach {
			tagString += "\n* Name: ${it.name}\n* Moderated: ${it.moderated}\n" +
				"* Emoji: ${if (it.emojiId != null) "<!${it.emojiId}>" else it.emojiName}\n---"
		}
		return tagString.ifNullOrEmpty { "None" }
	}

	/**
	 * Fills out the content for Guild event updates to avoid repeating code.
	 *
	 * @param event The event instance
	 * @param guild The guild for the event
	 * @author NoComment1105
	 * @since 5.0.0
	 */
	private suspend fun EmbedBuilder.guildEventEmbed(event: GuildScheduledEventEvent, guild: GuildBehavior) {
		field {
			name = "Event Name"
			value = event.scheduledEvent.name
		}
		field {
			name = "Event Description"
			value = event.scheduledEvent.description ?: "No description provided"
		}
		field {
			name = "Event location"
			value = if (event.scheduledEvent.channelId != null) {
				guild.getChannelOrNull(event.scheduledEvent.channelId!!)?.mention ?: "Unable to get channel"
			} else {
				"External event, no channel."
			}
		}
		field {
			name = "Start time"
			value = event.scheduledEvent.scheduledStartTime.toDiscord(TimestampType.ShortDateTime)
		}
		image = event.scheduledEvent.image?.cdnUrl?.toUrl().ifNullOrEmpty { "No image" }
	}
}
