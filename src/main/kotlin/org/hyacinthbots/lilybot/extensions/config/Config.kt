package org.hyacinthbots.lilybot.extensions.config

import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.boolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.coalescingOptionalDuration
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalChannel
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalRole
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalString
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.modules.unsafe.annotations.UnsafeAPI
import com.kotlindiscord.kord.extensions.modules.unsafe.extensions.unsafeSubCommand
import com.kotlindiscord.kord.extensions.modules.unsafe.types.InitialSlashCommandResponse
import com.kotlindiscord.kord.extensions.modules.unsafe.types.ackEphemeral
import com.kotlindiscord.kord.extensions.modules.unsafe.types.respondEphemeral
import com.kotlindiscord.kord.extensions.utils.botHasPermissions
import dev.kord.common.entity.Permission
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.embed
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.database.collections.LoggingConfigCollection
import org.hyacinthbots.lilybot.database.collections.ModerationConfigCollection
import org.hyacinthbots.lilybot.database.collections.UtilityConfigCollection
import org.hyacinthbots.lilybot.database.entities.LoggingConfigData
import org.hyacinthbots.lilybot.database.entities.ModerationConfigData
import org.hyacinthbots.lilybot.database.entities.PublicMemberLogData
import org.hyacinthbots.lilybot.database.entities.UtilityConfigData
import org.hyacinthbots.lilybot.utils.DEFAULT_BUNDLE_NAME
import org.hyacinthbots.lilybot.utils.canPingRole
import org.hyacinthbots.lilybot.utils.getLoggingChannelWithPerms
import org.hyacinthbots.lilybot.utils.interval
import org.hyacinthbots.lilybot.utils.trimmedContents

class Config : Extension() {
	override val name: String = "config"
	override val bundle: String = DEFAULT_BUNDLE_NAME

	@OptIn(UnsafeAPI::class)
	override suspend fun setup() {
		ephemeralSlashCommand {
			name = "extensions.config.config.name"
			description = "extensions.config.config.description"

			ephemeralSubCommand(::ModerationArgs) {
				name = "extensions.config.config.moderation.name"
				description = "extensions.config.config.moderation.description"

				requirePermission(Permission.ManageGuild)

				check {
					anyGuild()
					hasPermission(Permission.ManageGuild)
				}

				action {
					val moderationConfig = ModerationConfigCollection().getConfig(guild!!.id)
					if (moderationConfig != null) {
						respond {
							content = translate("extensions.config.config.configAlreadyExists", arrayOf("moderation"))
						}
						return@action
					}

					if (!arguments.enabled) {
						ModerationConfigCollection().setConfig(
							ModerationConfigData(
								guild!!.id,
								false,
								null,
								null,
								null,
								null,
								null,
								null
							)
						)
						respond {
							content = translate("extensions.config.config.moderation.systemDisabled")
						}
						return@action
					}

					if (
						arguments.moderatorRole != null && arguments.modActionLog == null ||
						arguments.moderatorRole == null && arguments.modActionLog != null
					) {
						respond {
							content =
								translate("extensions.config.config.moderation.roleAndChannelRequired")
						}
						return@action
					}

					if (!canPingRole(arguments.moderatorRole, guild!!.id, this@ephemeralSubCommand.kord)) {
						respond {
							content = translate(
								"extensions.config.config.moderation.roleNotPingable",
								arrayOf(arguments.moderatorRole!!.mention)
							)
						}
						return@action
					}

					val modActionLog: TextChannel?
					if (arguments.enabled && arguments.modActionLog != null) {
						modActionLog = guild!!.getChannelOfOrNull(arguments.modActionLog!!.id)
						if (modActionLog?.botHasPermissions(Permission.ViewChannel, Permission.SendMessages) != true) {
							respond {
								content =
									translate("extensions.config.config.invalidChannel", arrayOf("mod action log"))
							}
							return@action
						}
					}

					suspend fun EmbedBuilder.moderationEmbed() {
						title = translate("extensions.config.config.configurationEmbed.title", arrayOf("Moderation"))
						field {
							name = translate("extensions.config.config.moderation.embed.moderatorsField.name")
							value = arguments.moderatorRole?.mention ?: translate("basic.disabled")
						}
						field {
							name = translate("extensions.config.config.moderation.embed.actionLogField.name")
							value = arguments.modActionLog?.mention ?: translate("basic.disabled")
						}
						field {
							name = translate("extensions.config.config.moderation.embed.logPubliclyField.name")
							value = when (arguments.logPublicly) {
								true -> translate("basic.true")
								false -> translate("basic.disabled")
								null -> translate("basic.disabled")
							}
						}
						field {
							name = translate("extensions.config.config.moderation.embed.quickTimeoutLength.name")
							value = arguments.quickTimeoutLength.interval()
								?: translate("extensions.config.config.moderation.embed.quickTimeoutLength.disabled")
						}
						field {
							name = translate("extensions.config.config.moderation.embed.warningAutoPunishments.name")
							value = when (arguments.warnAutoPunishments) {
								true -> translate("basic.true")
								false -> translate("basic.disabled")
								null -> translate("basic.disabled")
							}
						}
						field {
							name = translate("extensions.config.config.moderation.embed.banDmMessage.name")
							value = arguments.banDmMessage
								?: translate("extensions.config.config.moderation.embed.banDmMessage.disabled")
						}
						footer {
							text = translate(
								"extensions.config.config.configuredBy",
								arrayOf(user.asUserOrNull()?.username)
							)
						}
					}

					respond {
						embed {
							moderationEmbed()
						}
					}

					ModerationConfigCollection().setConfig(
						ModerationConfigData(
							guild!!.id,
							arguments.enabled,
							arguments.modActionLog?.id,
							arguments.moderatorRole?.id,
							arguments.quickTimeoutLength,
							arguments.warnAutoPunishments,
							arguments.logPublicly,
							arguments.banDmMessage
						)
					)

					val utilityLog = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, this.getGuild()!!)

					if (utilityLog == null) {
						respond {
							content = translate("extensions.config.config.considerUtility")
						}
						return@action
					}

					utilityLog.createMessage {
						embed {
							moderationEmbed()
						}
					}
				}
			}

			unsafeSubCommand(::LoggingArgs) {
				name = "extensions.config.config.logging.name"
				description = "extensions.config.config.logging.description"

				initialResponse = InitialSlashCommandResponse.None

				requirePermission(Permission.ManageGuild)

				check {
					anyGuild()
					hasPermission(Permission.ManageGuild)
				}

				action {
					val loggingConfig = LoggingConfigCollection().getConfig(guild!!.id)
					if (loggingConfig != null) {
						ackEphemeral()
						respondEphemeral {
							content = translate("extensions.config.config.configAlreadyExists", arrayOf("logging"))
						}
						return@action
					}

					if (arguments.enableMemberLogging && arguments.memberLog == null) {
						ackEphemeral()
						respondEphemeral {
							content = translate("extensions.config.config.logging.memberMissing")
						}
						return@action
					} else if ((arguments.enableMessageDeleteLogs || arguments.enableMessageEditLogs) &&
						arguments.messageLogs == null
					) {
						ackEphemeral()
						respondEphemeral { content = translate("extensions.config.config.logging.editMissing") }
						return@action
					} else if (arguments.enablePublicMemberLogging && arguments.publicMemberLog == null) {
						ackEphemeral()
						respondEphemeral {
							content = translate("extensions.config.config.logging.pubicMemberMissing")
						}
						return@action
					}

					val memberLog: TextChannel?
					if (arguments.enableMemberLogging && arguments.memberLog != null) {
						memberLog = guild!!.getChannelOfOrNull(arguments.memberLog!!.id)
						if (memberLog?.botHasPermissions(Permission.ViewChannel, Permission.SendMessages) != true) {
							ackEphemeral()
							respondEphemeral {
								content = translate("extensions.config.config.invalidChannel", arrayOf("member log"))
							}
							return@action
						}
					}

					val messageLog: TextChannel?
					if ((arguments.enableMessageDeleteLogs || arguments.enableMessageEditLogs) && arguments.messageLogs != null) {
						messageLog = guild!!.getChannelOfOrNull(arguments.messageLogs!!.id)
						if (messageLog?.botHasPermissions(Permission.ViewChannel, Permission.SendMessages) != true) {
							ackEphemeral()
							respondEphemeral {
								content = translate("extensions.config.config.invalidChannel", arrayOf("message log"))
							}
							return@action
						}
					}

					val publicMemberLog: TextChannel?
					if (arguments.enablePublicMemberLogging && arguments.publicMemberLog != null) {
						publicMemberLog = guild!!.getChannelOfOrNull(arguments.publicMemberLog!!.id)
						if (publicMemberLog?.botHasPermissions(
								Permission.ViewChannel,
								Permission.SendMessages
							) != true
						) {
							ackEphemeral()
							respondEphemeral {
								content =
									translate("extensions.config.config.invalidChannel", arrayOf("public member log"))
							}
							return@action
						}
					}

					suspend fun EmbedBuilder.loggingEmbed() {
						title = translate("extensions.config.config.configurationEmbed.title", arrayOf("Logging"))
						field {
							name = translate("extensions.config.config.logging.embed.messageDeleteField.name")
							value = if (arguments.enableMessageDeleteLogs && arguments.messageLogs != null) {
								arguments.messageLogs!!.mention
							} else {
								translate("basic.disabled")
							}
						}
						field {
							name = translate("extensions.config.config.logging.embed.messageEditField.name")
							value = if (arguments.enableMessageEditLogs && arguments.messageLogs != null) {
								arguments.messageLogs!!.mention
							} else {
								translate("basic.disabled")
							}
						}
						field {
							name = translate("extensions.config.config.logging.embed.memberField.name")
							value = if (arguments.enableMemberLogging && arguments.memberLog != null) {
								arguments.memberLog!!.mention
							} else {
								translate("basic.disabled")
							}
						}

						field {
							name = translate("extensions.config.config.logging.embed.publicMemberField.ane")
							value = if (arguments.enablePublicMemberLogging && arguments.publicMemberLog != null) {
								arguments.publicMemberLog!!.mention
							} else {
								translate("basic.disabled")
							}
						}
						if (arguments.enableMemberLogging && arguments.publicMemberLog != null) {
							val config = LoggingConfigCollection().getConfig(guild!!.id)
							if (config != null) {
								field {
									name =
										translate("extensions.config.config.logging.embed.publicMemberField.joinMessage.name")
									value = config.publicMemberLogData?.joinMessage.trimmedContents(256)!!
								}
								field {
									name =
										translate("extensions.config.config.logging.embed.publicMemberField.leaveMessage.name")
									value = config.publicMemberLogData?.leaveMessage.trimmedContents(256)!!
								}
								field {
									name =
										translate("extensions.config.config.logging.embed.publicMemberField.pingOnJoin.name")
									value = config.publicMemberLogData?.pingNewUsers.toString()
								}
							}
						}

						footer {
							text = translate(
								"extensions.config.config.configuredBy",
								arrayOf(user.asUserOrNull()?.username)
							)
							icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
						}
					}

					var publicMemberLogData: PublicMemberLogData? = null
					if (arguments.enablePublicMemberLogging) {
						val modalObj = LoggingModal()

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

						publicMemberLogData = PublicMemberLogData(
							modalObj.ping.value == "yes",
							modalObj.joinMessage.value,
							modalObj.leaveMessage.value
						)
					}

					LoggingConfigCollection().setConfig(
						LoggingConfigData(
							guild!!.id,
							arguments.enableMessageDeleteLogs,
							arguments.enableMessageEditLogs,
							arguments.messageLogs?.id,
							arguments.enableMemberLogging,
							arguments.memberLog?.id,
							arguments.enablePublicMemberLogging,
							arguments.publicMemberLog?.id,
							publicMemberLogData
						)
					)

					if (!arguments.enablePublicMemberLogging) {
						ackEphemeral()
					}
					respondEphemeral {
						embed { loggingEmbed() }
					}

					val utilityLog = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, guild!!)

					if (utilityLog == null) {
						respondEphemeral {
							content = translate("extensions.config.config.considerUtility")
						}
						return@action
					}

					utilityLog.createMessage {
						embed {
							loggingEmbed()
						}
					}
				}
			}

			ephemeralSubCommand(::UtilityArgs) {
				name = "extensions.config.config.utility.name"
				description = "extensions.config.config.utility.description"

				requirePermission(Permission.ManageGuild)

				check {
					anyGuild()
					hasPermission(Permission.ManageGuild)
				}

				action {
					val utilityConfig = UtilityConfigCollection().getConfig(guild!!.id)

					if (utilityConfig != null) {
						respond {
							content = translate("extensions.config.config.configAlreadyExists", arrayOf("utility"))
						}
						return@action
					}

					var utilityLog: TextChannel? = null
					if (arguments.utilityLogChannel != null) {
						utilityLog = guild!!.getChannelOfOrNull(arguments.utilityLogChannel!!.id)
						if (utilityLog?.botHasPermissions(Permission.ViewChannel, Permission.SendMessages) != true) {
							respond {
								content = translate("extensions.config.config.invalidChannel", arrayOf("Utility Log"))
							}
							return@action
						}
					}

					suspend fun EmbedBuilder.utilityEmbed() {
						title = translate("extensions.config.config.configurationEmbed.title", arrayOf("Utility"))
						field {
							name = translate("extensions.config.config.utility.embed.utilityField.name")
							value = if (arguments.utilityLogChannel != null) {
								"${arguments.utilityLogChannel!!.mention} ${arguments.utilityLogChannel!!.data.name.value}"
							} else {
								translate("basic.disabled)")
							}
						}

						footer {
							text = translate(
								"extensions.config.config.configuredBy",
								arrayOf(user.asUserOrNull()?.username)
							)
							icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
						}
					}

					respond {
						embed {
							utilityEmbed()
						}
					}

					UtilityConfigCollection().setConfig(
						UtilityConfigData(
							guild!!.id,
							arguments.utilityLogChannel?.id
						)
					)

					utilityLog?.createMessage {
						embed {
							utilityEmbed()
						}
					}
				}
			}

			ephemeralSubCommand(::ClearArgs) {
				name = "extensions.config.config.clear.name"
				description = "extensions.config.config.clear.description"

				requirePermission(Permission.ManageGuild)

				check {
					anyGuild()
					hasPermission(Permission.ManageGuild)
				}

				action {
					suspend fun logClear() {
						val utilityLog = getLoggingChannelWithPerms(ConfigOptions.UTILITY_LOG, this.getGuild()!!)

						if (utilityLog == null) {
							respond {
								content = translate("extensions.config.config.considerUtility")
							}
							return
						}

						utilityLog.createMessage {
							embed {
								title = translate(
									"extensions.config.config.clear.embed.title",
									arrayOf(
										"${arguments.config[0]}${
											arguments.config.substring(
												1,
												arguments.config.length
											).lowercase()
										}"
									)
								)
								footer {
									text = translate(
										"extensions.config.config.clear.footer",
										arrayOf(user.asUserOrNull()?.username)
									)
									icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
								}
							}
						}
					}

					when (arguments.config) {
						ConfigType.MODERATION.name -> {
							ModerationConfigCollection().getConfig(guild!!.id) ?: run {
								respond {
									content =
										translate("extensions.config.config.clear.noConfig", arrayOf("moderation"))
								}
								return@action
							}

							logClear()

							ModerationConfigCollection().clearConfig(guild!!.id)
							respond {
								embed {
									title =
										translate("extensions.config.config.clear.embed.title", arrayOf("moderation"))
									footer {
										text = translate(
											"extensions.config.config.clear.footer",
											arrayOf(user.asUserOrNull()?.username)
										)
										icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
									}
								}
							}
						}

						ConfigType.LOGGING.name -> {
							LoggingConfigCollection().getConfig(guild!!.id) ?: run {
								respond {
									content = translate("extensions.config.config.clear.noConfig", arrayOf("logging"))
								}
								return@action
							}

							logClear()

							LoggingConfigCollection().clearConfig(guild!!.id)
							respond {
								embed {
									title = translate("extensions.config.config.clear.embed.title", arrayOf("logging"))
									footer {
										text = translate(
											"extensions.config.config.clear.footer",
											arrayOf(user.asUserOrNull()?.username)
										)
										icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
									}
								}
							}
						}

						ConfigType.UTILITY.name -> {
							UtilityConfigCollection().getConfig(guild!!.id) ?: run {
								respond {
									content = translate("extensions.config.config.clear.noConfig", arrayOf("utility"))
								}
								return@action
							}

							logClear()

							UtilityConfigCollection().clearConfig(guild!!.id)
							respond {
								embed {
									title = translate("extensions.config.config.clear.embed.title", arrayOf("utility"))
									footer {
										text = translate(
											"extensions.config.config.clear.footer",
											arrayOf(user.asUserOrNull()?.username)
										)
										icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
									}
								}
							}
						}

						ConfigType.ALL.name -> {
							ModerationConfigCollection().clearConfig(guild!!.id)
							LoggingConfigCollection().clearConfig(guild!!.id)
							UtilityConfigCollection().clearConfig(guild!!.id)
							respond {
								embed {
									title = translate("extensions.config.config.clear.all")
									footer {
										text = translate(
											"extensions.config.config.clear.footer",
											arrayOf(user.asUserOrNull()?.username)
										)
										icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
									}
								}
							}
						}
					}
				}
			}

			ephemeralSubCommand(::ViewArgs) {
				name = "extensions.config.config.view.name"
				description = "extensions.config.config.view.description"

				requirePermission(Permission.ManageGuild)

				check {
					anyGuild()
					hasPermission(Permission.ManageGuild)
				}

				action {
					when (arguments.config) {
						ConfigType.MODERATION.name -> {
							val config = ModerationConfigCollection().getConfig(guild!!.id)
							if (config == null) {
								respond {
									content = translate("extensions.config.config.view.noConfig", arrayOf("moderation"))
								}
								return@action
							}

							respond {
								embed {
									title = translate(
										"extensions.config.config.view.currentConfig.title",
										arrayOf("moderation")
									)
									description = translate(
										"extensions.config.config.view.currentConfig.description",
										arrayOf("moderation")
									)
									field {
										name = "${translate("basic.enabled")}/${translate("basic.disabled")}"
										value =
											if (config.enabled) translate("basic.enabled") else translate("basic.disabled")
									}
									field {
										name =
											translate("extensions.config.config.moderation.embed.moderatorsField.name")
										value = config.role?.let { guild!!.getRoleOrNull(it)?.mention }
											?: translate("basic.disabled")
									}
									field {
										name =
											translate("extensions.config.config.moderation.embed.actionLogField.name")
										value =
											config.channel?.let { guild!!.getChannelOrNull(it)?.mention }
												?: translate("basic.disabled")
									}
									field {
										name =
											translate("extensions.config.config.moderation.embed.logPubliclyField.name")
										value = when (config.publicLogging) {
											true -> translate("basic.true")
											false -> translate("basic.disabled")
											null -> translate("basic.disabled")
										}
									}
									field {
										name = translate("extensions.config.config.moderation.embed.banDmMessage.name")
										value = config.banDmMessage ?: translate("basic.none")
									}
									timestamp = Clock.System.now()
								}
							}
						}

						ConfigType.LOGGING.name -> {
							val config = LoggingConfigCollection().getConfig(guild!!.id)
							if (config == null) {
								respond {
									content = translate("extensions.config.config.view.noConfig", arrayOf("logging"))
								}
								return@action
							}

							respond {
								embed {
									title = translate(
										"extensions.config.config.view.currentConfig.title",
										arrayOf("logging")
									)
									description = translate(
										"extensions.config.config.view.currentConfig.description",
										arrayOf("logging")
									)
									field {
										name =
											translate("extensions.config.config.logging.embed.messageDeleteField.name")
										value = if (config.enableMessageDeleteLogs) {
											"${translate("basic.enabled")}\n" +
												"* ${guild!!.getChannelOrNull(config.messageChannel!!)?.mention ?: "Unable to get channel mention"} (" +
												"${guild!!.getChannelOrNull(config.messageChannel)?.name ?: "Unable to get channel name"})"
										} else {
											translate("basic.disabled")
										}
									}
									field {
										name = translate("extensions.config.config.logging.embed.messageEditField.name")
										value = if (config.enableMessageEditLogs) {
											"${translate("basic.enabled")}\n" +
												"* ${guild!!.getChannelOrNull(config.messageChannel!!)?.mention ?: "Unable to get channel mention"} (" +
												"${guild!!.getChannelOrNull(config.messageChannel)?.name ?: "Unable to get channel mention"})"
										} else {
											translate("basic.disabled")
										}
									}
									field {
										name = translate("extensions.config.config.logging.embed.memberField.name")
										value = if (config.enableMemberLogs) {
											"${translate("basic.enabled")}\n" +
												"* ${guild!!.getChannelOrNull(config.memberLog!!)?.mention ?: "Unable to get channel mention"} (" +
												"${guild!!.getChannelOrNull(config.memberLog)?.name ?: "Unable to get channel mention."})"
										} else {
											translate("basic.disabled")
										}
									}
									timestamp = Clock.System.now()
								}
							}
						}

						ConfigType.UTILITY.name -> {
							val config = UtilityConfigCollection().getConfig(guild!!.id)
							if (config == null) {
								respond {
									content = translate("extensions.config.config.view.noConfig", arrayOf("utility"))
								}
								return@action
							}

							respond {
								embed {
									title = translate(
										"extensions.config.config.view.currentConfig.title",
										arrayOf("utility")
									)
									description = translate(
										"extensions.config.config.view.currentConfig.description",
										arrayOf("utility")
									)
									field {
										name = translate("extensions.config.config.utility.embed.utilityField.name")
										value =
											"${
												config.utilityLogChannel?.let { guild!!.getChannelOrNull(it)?.mention } ?: translate(
													"basic.none"
												)
											} ${config.utilityLogChannel?.let { guild!!.getChannelOrNull(it)?.name } ?: ""}"
									}
									timestamp = Clock.System.now()
								}
							}
						}
					}
				}
			}
		}
	}

	inner class ModerationArgs : Arguments() {
		val enabled by boolean {
			name = "extensions.config.config.arguments.moderation.enabled.name"
			description = "extensions.config.config.arguments.moderation.enabled.description"
		}

		val moderatorRole by optionalRole {
			name = "extensions.config.config.arguments.moderatorRole.enabled.name"
			description = "extensions.config.config.arguments.moderatorRole.enabled.description"
		}

		val modActionLog by optionalChannel {
			name = "extensions.config.config.arguments.modActionLog.enabled.name"
			description = "extensions.config.config.arguments.modActionLog.enabled.description"
		}

		val quickTimeoutLength by coalescingOptionalDuration {
			name = "extensions.config.config.arguments.quickTimeout.enabled.name"
			description = "extensions.config.config.arguments.quickTimeout.enabled.description"
		}

		val warnAutoPunishments by optionalBoolean {
			name = "extensions.config.config.arguments.auto-punish.enabled.name"
			description = "extensions.config.config.arguments.auto-punish.enabled.description"
		}

		val logPublicly by optionalBoolean {
			name = "extensions.config.config.arguments.logPublicly.enabled.name"
			description = "extensions.config.config.arguments.logPublicly.enabled.description"
		}

		val banDmMessage by optionalString {
			name = "extensions.config.config.arguments.banDm.enabled.name"
			description = "extensions.config.config.arguments.banDm.enabled.description"
		}
	}

	inner class LoggingArgs : Arguments() {
		val enableMessageDeleteLogs by boolean {
			name = "extensions.config.config.arguments.logging.enableDelete.name"
			description = "extensions.config.config.arguments.logging.enableDelete.description"
		}

		val enableMessageEditLogs by boolean {
			name = "extensions.config.config.arguments.logging.enableEdit.name"
			description = "extensions.config.config.arguments.logging.enableEdit.description"
		}

		val enableMemberLogging by boolean {
			name = "extensions.config.config.arguments.logging.enableMember.name"
			description = "extensions.config.config.arguments.logging.enableMember.description"
		}

		val enablePublicMemberLogging by boolean {
			name = "extensions.config.config.arguments.logging.enablePublicMember.name"
			description =
				"extensions.config.config.arguments.logging.enablePublicMember.description"
		}

		val messageLogs by optionalChannel {
			name = "extensions.config.config.arguments.logging.messageLog.name"
			description = "extensions.config.config.arguments.logging.messageLog.description"
		}

		val memberLog by optionalChannel {
			name = "extensions.config.config.arguments.logging.memberLog.name"
			description = "extensions.config.config.arguments.logging.memberLog.description"
		}

		val publicMemberLog by optionalChannel {
			name = "extensions.config.config.arguments.logging.publicMemberLog.name"
			description = "extensions.config.config.arguments.logging.publicMemberLog.description"
		}
	}

	inner class UtilityArgs : Arguments() {
		val utilityLogChannel by optionalChannel {
			name = "extensions.config.config.arguments.utility.utilityLog.name"
			description = "extensions.config.config.arguments.utility.utilityLog.description"
		}
	}

	inner class ClearArgs : Arguments() {
		val config by stringChoice {
			name = "extensions.config.config.arguments.clear.name"
			description = "extensions.config.config.arguments.clear.description"
			choices = mutableMapOf(
				"extensions.config.config.arguments.clear.choice.moderation" to ConfigType.MODERATION.name,
				"extensions.config.config.arguments.clear.choice.logging" to ConfigType.LOGGING.name,
				"extensions.config.config.arguments.clear.choice.utility" to ConfigType.UTILITY.name,
				"extensions.config.config.arguments.clear.choice.all" to ConfigType.ALL.name
			)
		}
	}

	inner class ViewArgs : Arguments() {
		val config by stringChoice {
			name = "extensions.config.config.arguments.clear.name"
			description = "extensions.config.config.arguments.view.description"
			choices = mutableMapOf(
				"extensions.config.config.arguments.clear.choice.moderation" to ConfigType.MODERATION.name,
				"extensions.config.config.arguments.clear.choice.logging" to ConfigType.LOGGING.name,
				"extensions.config.config.arguments.clear.choice.utility" to ConfigType.UTILITY.name,
			)
		}
	}

	inner class LoggingModal : ModalForm() {
		override var title = "extensions.config.config.logging.modal.title"

		val joinMessage = paragraphText {
			label = "extensions.config.config.logging.modal.joinMessage.label"
			placeholder = "extensions.config.config.logging.modal.joinMessage.placeholder"
			required = true
		}

		val leaveMessage = paragraphText {
			label = "extensions.config.config.logging.modal.leaveMessage.label"
			placeholder = "extensions.config.config.logging.modal.leaveMessage.placeholder"
			required = true
		}

		val ping = lineText {
			label = "extensions.config.config.logging.modal.ping.label"
			placeholder = "extensions.config.config.logging.modal.ping.placeholder"
		}
	}
}
