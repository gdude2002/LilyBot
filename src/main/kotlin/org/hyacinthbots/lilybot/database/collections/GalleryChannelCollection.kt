package org.hyacinthbots.lilybot.database.collections

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.flow.toList
import org.hyacinthbots.lilybot.database.Database
import org.hyacinthbots.lilybot.database.entities.GalleryChannelData
import org.koin.core.component.inject

/**
 * This class contains the functions for interacting with the [Gallery Channel Database][GalleryChannelData]. This
 * class contains functions for getting, setting and removing gallery channels.
 *
 * @since 4.0.0
 * @see getChannels
 * @see setChannel
 * @see removeChannel
 * @see removeAll
 */
class GalleryChannelCollection : KordExKoinComponent {
	private val db: Database by inject()

	@PublishedApi
	internal val collection = db.mainDatabase.getCollection<GalleryChannelData>(GalleryChannelData.name)

	/**
	 * Collects every gallery channel in the database into a [List].
	 *
	 * @return The [MongoCollection] of [GalleryChannelData] for all the gallery channels in the database
	 * @author NoComment1105
	 * @since 3.3.0
	 */
	suspend inline fun getChannels(inputGuildId: Snowflake): List<GalleryChannelData> =
		collection.find(eq(GalleryChannelData::guildId.name, inputGuildId)).toList()

	/**
	 * Stores a channel ID as input by the user, in the database, with it's corresponding guild, allowing us to find
	 * the channel later.
	 *
	 * @param inputGuildId The guild the channel is in
	 * @param inputChannelId The channel that is being set as a gallery channel
	 * @author NoComment1105
	 * @since 3.3.0
	 */
	suspend inline fun setChannel(inputGuildId: Snowflake, inputChannelId: Snowflake) =
		collection.insertOne(GalleryChannelData(inputGuildId, inputChannelId))

	/**
	 * Removes a channel ID from the gallery channel database.
	 *
	 * @param inputGuildId The guild the channel is in
	 * @param inputChannelId The channel being removed
	 * @author NoComment1105
	 * @since 3.3.0
	 */
	suspend inline fun removeChannel(inputGuildId: Snowflake, inputChannelId: Snowflake) =
		collection.deleteOne(
			and(
				eq(GalleryChannelData::channelId.name, inputChannelId),
				eq(GalleryChannelData::guildId.name, inputGuildId)
			)
		)

	/**
	 * Removes all gallery channels from this guild.
	 *
	 * @param inputGuildId The guild to clear the gallery channels from
	 * @author NoComment1105
	 * @since 4.1.0
	 */
	suspend inline fun removeAll(inputGuildId: Snowflake) =
		collection.deleteMany(eq(GalleryChannelData::guildId.name, inputGuildId))
}
