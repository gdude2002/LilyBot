package org.hyacinthbots.lilybot.extensions.util

import com.kotlindiscord.kord.extensions.DISCORD_GREEN
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.DISCORD_YELLOW
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.checks.guildFor
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.dm
import com.kotlindiscord.kord.extensions.utils.getTopRole
import com.kotlindiscord.kord.extensions.utils.hasPermission
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Permission
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.request.KtorRequestException
import kotlinx.datetime.Clock
import org.hyacinthbots.lilybot.database.collections.UtilityConfigCollection
import org.hyacinthbots.lilybot.extensions.config.ConfigOptions
import org.hyacinthbots.lilybot.utils.DEFAULT_BUNDLE_NAME
import org.hyacinthbots.lilybot.utils.requiredConfigs

/**
 * This class contains a few utility commands that can be used by the public in guilds, or that are often seen by the
 * public.
 *
 * @since 3.1.0
 */
class PublicUtilities : Extension() {
	override val name = "public-utilities"
	override val bundle = DEFAULT_BUNDLE_NAME

	override suspend fun setup() {
		/**
		 * Ping Command.
		 * @author IMS212
		 * @since 2.0
		 */
		publicSlashCommand {
			name = "extensions.public-utilities.ping.name"
			description = "extensions.public-utilities.ping.description"

			action {
				val averagePing = this@PublicUtilities.kord.gateway.averagePing

				respond {
					embed {
						color = DISCORD_YELLOW
						title = translate("extensions.public-utilities.ping.title")

						timestamp = Clock.System.now()

						field {
							name = translate("extensions.public-utilities.pingEmbed.pingValue.title")
							value = "**$averagePing**"
							inline = true
						}
					}
				}
			}
		}

		/**
		 * Nickname request command
		 * @author NoComment1105
		 * @since 3.1.0
		 */
		ephemeralSlashCommand {
			name = "extensions.public-utilities.nickname.name"
			description = "extensions.public-utilities.nickname.description"

			ephemeralSubCommand(::NickRequestArgs) {
				name = "extensions.public-utilities.nickname.request.name"
				description = "extensions.public-utilities.nickname.request.description"

				check {
					anyGuild()
					requiredConfigs(ConfigOptions.UTILITY_LOG)
				}

				action {
					val config = UtilityConfigCollection().getConfig(guildFor(event)!!.id)!!
					val utilityLog = guild?.getChannelOfOrNull<GuildMessageChannel>(config.utilityLogChannel!!)

					val requester = user.asUserOrNull()
					val requesterAsMember = requester?.asMemberOrNull(guild!!.id)
					val self = this@PublicUtilities.kord.getSelf().asMemberOrNull(guild!!.id)

					if (requesterAsMember?.getTopRole()?.getPosition() != null &&
						self?.getTopRole()?.getPosition() == null
					) {
						respond {
							content = translate("extensions.public-utilities.nickname.request.lilyNoRole.public")
						}
						return@action
					} else if ((requesterAsMember?.getTopRole()?.getPosition() ?: 0) >
						(self?.getTopRole()?.getPosition() ?: 0)
					) {
						respond {
							content = translate("extensions.public-utilities.nickname.request.highestRole.public")
						}
						return@action
					}

					if (requesterAsMember?.hasPermission(Permission.ChangeNickname) == true) {
						requesterAsMember.edit { nickname = arguments.newNick }
						respond {
							content = translate("extensions.public-utilities.nickname.request.hasPermission")
							return@action
						}
					}

					// Declare the embed outside the action to allow us to reference it inside the action
					var actionLogEmbed: Message? = null

					respond { content = translate("extensions.public-utilities.nickname.request.sent") }

					try {
						actionLogEmbed =
							utilityLog?.createMessage {
								embed {
									color = DISCORD_YELLOW
									title = translate("extensions.public-utilities.nickname.request.requestEmbed.title")
									timestamp = Clock.System.now()

									field {
										name = translate("extensions.public-utilities.nickname.userField")
										value =
											"${requester?.mention}\n${requester?.asUserOrNull()?.username}\n${requester?.id}"
										inline = false
									}

									field {
										name =
											translate("extensions.public-utilities.nickname.request.requestEmbed.currentNick")
										value = "`${requesterAsMember?.nickname}`"
										inline = false
									}

									field {
										name =
											translate("extensions.public-utilities.nickname.request.requestEmbed.requestedNick")
										value = "`${arguments.newNick}`"
										inline = false
									}
								}
								components {
									ephemeralButton(row = 0) {
										label = translate("extensions.public-utilities.nickname.request.button.accept")
										style = ButtonStyle.Success

										action button@{
											if (requesterAsMember?.getTopRole()?.getPosition() != null &&
												self?.getTopRole()?.getPosition() == null
											) {
												respond {
													content =
														translate("extensions.public-utilities.nickname.request.lilyNoRole.private")
												}
												return@button
											} else if ((requesterAsMember?.getTopRole()?.getPosition() ?: 0) >
												(self?.getTopRole()?.getPosition() ?: 0)
											) {
												respond {
													content =
														translate("extensions.public-utilities.nickname.request.highestRole.private")
												}
												return@button
											}

											requesterAsMember?.edit { nickname = arguments.newNick }

											requester?.dm {
												embed {
													title = translate(
														"extensions.public-utilities.nickname.request.dm.accept.title",
														arrayOf(guild!!.asGuildOrNull()?.name)
													)
													description = translate(
														"extensions.public-utilities.nickname.request.dm.accept.description",
														arrayOf(requesterAsMember?.nickname, arguments.newNick)
													)
													color = DISCORD_GREEN
												}
											}

											actionLogEmbed!!.edit {
												components { removeAll() }

												embed {
													color = DISCORD_GREEN
													title =
														translate("extensions.public-utilities.nickname.request.logEmbed.accept.title")

													field {
														name =
															translate("extensions.public-utilities.nickname.userField")
														value =
															"${requester?.mention}\n${requester?.asUserOrNull()?.username}\n" +
																	"${requester?.id}"
														inline = false
													}

													// these two fields should be the same and exist as a sanity check
													field {
														name =
															translate("extensions.public-utilities.nickname.request.logEmbed.accept.previousNick")
														value = "`${requesterAsMember?.nickname}`"
														inline = false
													}

													field {
														name =
															translate("extensions.public-utilities.nickname.request.logEmbed.accept.acceptedNick")
														value = "`${arguments.newNick}`"
														inline = false
													}

													footer {
														text = translate(
															"extensions.public-utilities.nickname.request.logEmbed.accept.acceptedBy",
															arrayOf(user.asUserOrNull()?.username)
														)
														icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
													}

													timestamp = Clock.System.now()
												}
											}
										}
									}

									ephemeralButton(row = 0) {
										label = translate("extensions.public-utilities.nickname.request.button.deny")
										style = ButtonStyle.Danger

										action {
											requester?.dm {
												embed {
													title =
														translate("extensions.public-utilities.nickname.request.dm.deny.title")
													description = translate(
														"extensions.public-utilities.nickname.request.dm.deny.description",
														arrayOf(arguments.newNick)
													)
												}
											}

											actionLogEmbed!!.edit {
												components { removeAll() }
												embed {
													title =
														translate("extensions.public-utilities.nickname.request.logEmbed.deny.title")

													field {
														name =
															translate("extensions.public-utilities.nickname.userField")
														value = "${requester?.mention}\n" +
																"${requester?.asUserOrNull()?.username}\n${requester?.id}"
														inline = false
													}

													field {
														name =
															translate("extensions.public-utilities.nickname.request.logEmbed.deny.currentNick")
														value = "`${requesterAsMember?.nickname}`"
														inline = false
													}

													field {
														name =
															translate("extensions.public-utilities.nickname.request.logEmbed.deny.rejectedNick")
														value = "`${arguments.newNick}`"
														inline = false
													}

													footer {
														text = translate(
															"extensions.public-utilities.nickname.request.logEmbed.deny.deniedBy",
															arrayOf(user.asUserOrNull()?.username)
														)
														icon = user.asUserOrNull()?.avatar?.cdnUrl?.toUrl()
													}

													timestamp = Clock.System.now()
													color = DISCORD_RED
												}
											}
										}
									}
								}
							}
					} catch (e: KtorRequestException) {
						// Avoid hard failing on permission error, since the public won't know what it means
						respond {
							content = translate("extensions.public-utilities.nickname.failToSend")
						}
						return@action
					}
				}
			}

			ephemeralSubCommand {
				name = "extensions.public-utilities.nickname.clear.name"
				description = "extensions.public-utilities.nickname.clear.description"

				check {
					anyGuild()
					requiredConfigs(ConfigOptions.UTILITY_LOG)
				}

				action {
					val config = UtilityConfigCollection().getConfig(guild!!.id)!!
					val utilityLog = guild?.getChannelOfOrNull<GuildMessageChannel>(config.utilityLogChannel!!)

					// Check the user has a nickname to clear, avoiding errors and useless action-log notifications
					if (user.fetchMember(guild!!.id).nickname == null) {
						respond { content = translate("extensions.public-utilities.nickname.clear.nothingToClear") }
						return@action
					}

					respond { content = translate("extensions.public-utilities.nickname.clear.cleared") }

					try {
						utilityLog?.createEmbed {
							title = translate("extensions.public-utilities.nickname.clear.logEmbed.title")
							color = DISCORD_YELLOW
							timestamp = Clock.System.now()

							field {
								name = translate("extensions.public-utilities.nickname.userField")
								value = "${user.mention}\n${user.asUserOrNull()?.username}\n${user.id}"
								inline = false
							}

							field {
								name = translate("extensions.public-utilities.nickname.clear.newNickField.title")
								value = translate(
									"extensions.public-utilities.nickname.clear.newNickField.value",
									arrayOf(user.asMemberOrNull(guild!!.id)?.nickname)
								)
								inline = false
							}
						}
					} catch (_: KtorRequestException) {
						// Avoid hard failing on permission error, since the public won't know what it means
						respond {
							content = translate("extensions.public-utilities.nickname.failToSend")
						}
						return@action
					}
					user.asMemberOrNull(guild!!.id)?.edit { nickname = null }
				}
			}
		}
	}

	inner class NickRequestArgs : Arguments() {
		/** The new nickname that the command user requested. */
		val newNick by string {
			name = "extensions.public-utilities.nickname.request.args.newNick.name"
			description = "extensions.public-utilities.nickname.request.args.newNick.description"

			minLength = 1
			maxLength = 32
		}
	}
}
