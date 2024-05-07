/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.config.init

import io.airbyte.commons.json.Jsons
import io.airbyte.commons.version.AirbyteProtocolVersionRange
import io.airbyte.commons.version.Version
import io.airbyte.config.ActorDefinitionVersion
import io.airbyte.config.BreakingChanges
import io.airbyte.config.ConnectorRegistryDestinationDefinition
import io.airbyte.config.ConnectorRegistrySourceDefinition
import io.airbyte.config.ConnectorReleases
import io.airbyte.config.VersionBreakingChange
import io.airbyte.config.helpers.ConnectorRegistryConverters
import io.airbyte.config.init.ApplyDefinitionMetricsHelper.DefinitionProcessingFailureReason
import io.airbyte.config.init.ApplyDefinitionMetricsHelper.DefinitionProcessingSuccessOutcome
import io.airbyte.config.persistence.ConfigNotFoundException
import io.airbyte.config.specs.DefinitionsProvider
import io.airbyte.data.services.ActorDefinitionService
import io.airbyte.data.services.DestinationService
import io.airbyte.data.services.SourceService
import io.airbyte.metrics.lib.MetricAttribute
import io.airbyte.metrics.lib.MetricClient
import io.airbyte.metrics.lib.OssMetricsRegistry
import io.airbyte.persistence.job.JobPersistence
import io.airbyte.protocol.models.ConnectorSpecification
import io.airbyte.validation.json.JsonValidationException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.Optional
import java.util.UUID

/**
 * Test suite for the [ApplyDefinitionsHelper] class.
 */
internal class ApplyDefinitionsHelperTest {
  private var definitionsProvider: DefinitionsProvider = mockk()
  private var jobPersistence: JobPersistence = mockk()
  private var actorDefinitionService: ActorDefinitionService = mockk()
  private var sourceService: SourceService = mockk()
  private var destinationService: DestinationService = mockk()
  private var metricClient: MetricClient = mockk()
  private var supportStateUpdater: SupportStateUpdater = mockk()
  private lateinit var applyDefinitionsHelper: ApplyDefinitionsHelper

  @BeforeEach
  fun setup() {
    applyDefinitionsHelper =
      ApplyDefinitionsHelper(
        definitionsProvider,
        jobPersistence,
        actorDefinitionService,
        sourceService,
        destinationService,
        metricClient,
        supportStateUpdater,
      )

    every { actorDefinitionService.actorDefinitionIdsInUse } returns emptySet()
    every { actorDefinitionService.actorDefinitionIdsToDefaultVersionsMap } returns emptyMap()
    every { definitionsProvider.getDestinationDefinitions() } returns emptyList()
    every { definitionsProvider.getSourceDefinitions() } returns emptyList()
    every { jobPersistence.currentProtocolVersionRange } returns Optional.of(AirbyteProtocolVersionRange(Version("2.0.0"), Version("3.0.0")))
    mockVoidReturningFunctions()
  }

  private fun mockVoidReturningFunctions() {
    justRun { sourceService.writeConnectorMetadata(any(), any(), any()) }
    justRun { sourceService.updateStandardSourceDefinition(any()) }
    justRun { destinationService.writeConnectorMetadata(any(), any(), any()) }
    justRun { destinationService.updateStandardDestinationDefinition(any()) }
    justRun { metricClient.count(any(), any(), *anyVararg<MetricAttribute>()) }
    justRun { supportStateUpdater.updateSupportStates() }
  }

  @Throws(IOException::class)
  private fun mockSeedInitialDefinitions() {
    val seededDefinitionsAndDefaultVersions: MutableMap<UUID, ActorDefinitionVersion> = HashMap()
    seededDefinitionsAndDefaultVersions[POSTGRES_ID] =
      ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES)
    seededDefinitionsAndDefaultVersions[S3_ID] =
      ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3)
    every { actorDefinitionService.actorDefinitionIdsToDefaultVersionsMap } returns seededDefinitionsAndDefaultVersions
  }

  @Throws(IOException::class)
  private fun verifyActorDefinitionServiceInteractions() {
    verify { actorDefinitionService.actorDefinitionIdsToDefaultVersionsMap }
    verify { actorDefinitionService.actorDefinitionIdsInUse }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  @Throws(
    IOException::class,
    JsonValidationException::class,
    ConfigNotFoundException::class,
    io.airbyte.data.exceptions.ConfigNotFoundException::class,
  )
  fun `a new connector should always be written`(updateAll: Boolean) {
    every { definitionsProvider.sourceDefinitions } returns listOf(SOURCE_POSTGRES)
    every { definitionsProvider.destinationDefinitions } returns listOf(DESTINATION_S3)

    applyDefinitionsHelper.apply(updateAll)
    verifyActorDefinitionServiceInteractions()

    verify {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES),
        ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(SOURCE_POSTGRES),
      )
    }
    verify {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3),
        ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(DESTINATION_S3),
      )
    }
    listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach {
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "ok"),
          MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.INITIAL_VERSION_ADDED.toString()),
          MetricAttribute("docker_repository", it),
          MetricAttribute("docker_image_tag", INITIAL_CONNECTOR_VERSION),
        )
      }
    }
    verify { supportStateUpdater.updateSupportStates() }

    confirmVerified(actorDefinitionService, sourceService, destinationService, supportStateUpdater, metricClient)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  @Throws(
    IOException::class,
    JsonValidationException::class,
    ConfigNotFoundException::class,
    io.airbyte.data.exceptions.ConfigNotFoundException::class,
  )
  fun `an existing connector that is not in use should always be updated`(updateAll: Boolean) {
    mockSeedInitialDefinitions()
    every { actorDefinitionService.actorDefinitionIdsInUse } returns emptySet()

    // New definitions come in
    every { definitionsProvider.sourceDefinitions } returns listOf(SOURCE_POSTGRES_2)
    every { definitionsProvider.destinationDefinitions } returns listOf(DESTINATION_S3_2)

    applyDefinitionsHelper.apply(updateAll)
    verifyActorDefinitionServiceInteractions()

    verify {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES_2),
        ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES_2),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(SOURCE_POSTGRES_2),
      )
    }
    verify {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3_2),
        ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3_2),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(DESTINATION_S3_2),
      )
    }
    listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach {
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "ok"),
          MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.DEFAULT_VERSION_UPDATED.toString()),
          MetricAttribute("docker_repository", it),
          MetricAttribute("docker_image_tag", UPDATED_CONNECTOR_VERSION),
        )
      }
    }
    verify { supportStateUpdater.updateSupportStates() }

    confirmVerified(actorDefinitionService, sourceService, destinationService, supportStateUpdater, metricClient)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  @Throws(
    IOException::class,
    JsonValidationException::class,
    ConfigNotFoundException::class,
    io.airbyte.data.exceptions.ConfigNotFoundException::class,
  )
  fun `updateAll should affect whether existing connectors in use have their versions updated`(updateAll: Boolean) {
    mockSeedInitialDefinitions()
    every { actorDefinitionService.actorDefinitionIdsInUse } returns setOf(POSTGRES_ID, S3_ID)

    every { definitionsProvider.sourceDefinitions } returns listOf(SOURCE_POSTGRES_2)
    every { definitionsProvider.destinationDefinitions } returns listOf(DESTINATION_S3_2)

    applyDefinitionsHelper.apply(updateAll)
    verifyActorDefinitionServiceInteractions()

    if (updateAll) {
      verify {
        sourceService.writeConnectorMetadata(
          ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES_2),
          ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES_2),
          ConnectorRegistryConverters.toActorDefinitionBreakingChanges(SOURCE_POSTGRES_2),
        )
      }
      verify {
        destinationService.writeConnectorMetadata(
          ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3_2),
          ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3_2),
          ConnectorRegistryConverters.toActorDefinitionBreakingChanges(DESTINATION_S3_2),
        )
      }
      listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach { dockerRepo ->
        verify {
          metricClient.count(
            OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
            1,
            MetricAttribute("status", "ok"),
            MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.DEFAULT_VERSION_UPDATED.toString()),
            MetricAttribute("docker_repository", dockerRepo),
            MetricAttribute("docker_image_tag", UPDATED_CONNECTOR_VERSION),
          )
        }
      }
    } else {
      verify { sourceService.updateStandardSourceDefinition(ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES_2)) }
      verify {
        destinationService.updateStandardDestinationDefinition(
          ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3_2),
        )
      }
      verify(exactly = 2) {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "ok"),
          MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.VERSION_UNCHANGED.toString()),
        )
      }
    }
    verify { supportStateUpdater.updateSupportStates() }

    confirmVerified(actorDefinitionService, sourceService, destinationService, supportStateUpdater, metricClient)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  @Throws(
    JsonValidationException::class,
    IOException::class,
    ConfigNotFoundException::class,
    io.airbyte.data.exceptions.ConfigNotFoundException::class,
  )
  fun `new definitions that are incompatible with the protocol version range should not be written`(updateAll: Boolean) {
    every { jobPersistence.currentProtocolVersionRange } returns Optional.of(AirbyteProtocolVersionRange(Version("2.0.0"), Version("3.0.0")))
    val postgresWithOldProtocolVersion = Jsons.clone(SOURCE_POSTGRES).withSpec(ConnectorSpecification().withProtocolVersion("1.0.0"))
    val s3withOldProtocolVersion = Jsons.clone(DESTINATION_S3).withSpec(ConnectorSpecification().withProtocolVersion("1.0.0"))

    every { definitionsProvider.sourceDefinitions } returns listOf(postgresWithOldProtocolVersion, SOURCE_POSTGRES_2)
    every { definitionsProvider.destinationDefinitions } returns listOf(s3withOldProtocolVersion, DESTINATION_S3_2)

    applyDefinitionsHelper.apply(updateAll)
    verifyActorDefinitionServiceInteractions()

    listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach {
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "failed"),
          MetricAttribute("outcome", DefinitionProcessingFailureReason.INCOMPATIBLE_PROTOCOL_VERSION.toString()),
          MetricAttribute("docker_repository", it),
          MetricAttribute("docker_image_tag", INITIAL_CONNECTOR_VERSION),
        )
      }
    }

    verify(exactly = 0) {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(postgresWithOldProtocolVersion),
        ConnectorRegistryConverters.toActorDefinitionVersion(s3withOldProtocolVersion),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(postgresWithOldProtocolVersion),
      )
    }
    verify(exactly = 0) {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(s3withOldProtocolVersion),
        ConnectorRegistryConverters.toActorDefinitionVersion(postgresWithOldProtocolVersion),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(s3withOldProtocolVersion),
      )
    }
    verify {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES_2),
        ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES_2),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(SOURCE_POSTGRES_2),
      )
    }
    verify {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3_2),
        ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3_2),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(DESTINATION_S3_2),
      )
    }
    verify { supportStateUpdater.updateSupportStates() }
    listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach {
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "ok"),
          MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.INITIAL_VERSION_ADDED.toString()),
          MetricAttribute("docker_repository", it),
          MetricAttribute("docker_image_tag", UPDATED_CONNECTOR_VERSION),
        )
      }
    }
    confirmVerified(actorDefinitionService, sourceService, destinationService, supportStateUpdater, metricClient)
  }

  @Test
  fun `one malformed definition should not be written, but shouldn't stop others from being written`() {
    val malformedRegistrySourceDefinition =
      Jsons.clone(SOURCE_POSTGRES).withDockerImageTag("a-non-semantic-version-for-example")
    assertThrows<RuntimeException> { ConnectorRegistryConverters.toActorDefinitionVersion(malformedRegistrySourceDefinition) }

    val malformedRegistryDestinationDefinition =
      Jsons.clone(DESTINATION_S3).withDockerImageTag("a-non-semantic-version-for-example")
    assertThrows<RuntimeException> {
      ConnectorRegistryConverters.toActorDefinitionVersion(
        malformedRegistryDestinationDefinition,
      )
    }

    val anotherNewSourceDefinition =
      Jsons.clone(SOURCE_POSTGRES).withName("new").withDockerRepository("airbyte/source-new").withSourceDefinitionId(UUID.randomUUID())
    val anotherNewDestinationDefinition =
      Jsons.clone(DESTINATION_S3).withName("new").withDockerRepository("airbyte/destination-new").withDestinationDefinitionId(UUID.randomUUID())

    every { definitionsProvider.sourceDefinitions } returns listOf(SOURCE_POSTGRES, malformedRegistrySourceDefinition, anotherNewSourceDefinition)
    every {
      definitionsProvider.destinationDefinitions
    } returns listOf(DESTINATION_S3, malformedRegistryDestinationDefinition, anotherNewDestinationDefinition)

    applyDefinitionsHelper.apply(true)
    verifyActorDefinitionServiceInteractions()
    listOf("airbyte/source-postgres", "airbyte/destination-s3").forEach { dockerRepo ->
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "failed"),
          MetricAttribute("outcome", DefinitionProcessingFailureReason.DEFINITION_CONVERSION_FAILED.toString()),
          MetricAttribute("docker_repository", dockerRepo),
          MetricAttribute("docker_image_tag", "a-non-semantic-version-for-example"),
        )
      }
    }
    verify {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(SOURCE_POSTGRES),
        ConnectorRegistryConverters.toActorDefinitionVersion(SOURCE_POSTGRES),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(SOURCE_POSTGRES),
      )
    }
    verify {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(DESTINATION_S3),
        ConnectorRegistryConverters.toActorDefinitionVersion(DESTINATION_S3),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(DESTINATION_S3),
      )
    }
    verify {
      sourceService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardSourceDefinition(anotherNewSourceDefinition),
        ConnectorRegistryConverters.toActorDefinitionVersion(anotherNewSourceDefinition),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(anotherNewSourceDefinition),
      )
    }
    verify {
      destinationService.writeConnectorMetadata(
        ConnectorRegistryConverters.toStandardDestinationDefinition(anotherNewDestinationDefinition),
        ConnectorRegistryConverters.toActorDefinitionVersion(anotherNewDestinationDefinition),
        ConnectorRegistryConverters.toActorDefinitionBreakingChanges(anotherNewDestinationDefinition),
      )
    }
    verify { supportStateUpdater.updateSupportStates() }
    listOf("airbyte/source-postgres", "airbyte/destination-s3", "airbyte/source-new", "airbyte/destination-new").forEach { dockerRepo ->
      verify {
        metricClient.count(
          OssMetricsRegistry.CONNECTOR_REGISTRY_DEFINITION_PROCESSED,
          1,
          MetricAttribute("status", "ok"),
          MetricAttribute("outcome", DefinitionProcessingSuccessOutcome.INITIAL_VERSION_ADDED.toString()),
          MetricAttribute("docker_repository", dockerRepo),
          MetricAttribute("docker_image_tag", INITIAL_CONNECTOR_VERSION),
        )
      }
    }

    // The malformed definitions should not have been written.
    confirmVerified(actorDefinitionService, sourceService, destinationService, supportStateUpdater, metricClient)
  }

  companion object {
    private const val INITIAL_CONNECTOR_VERSION = "0.1.0"
    private const val UPDATED_CONNECTOR_VERSION = "0.2.0"
    private const val BREAKING_CHANGE_VERSION = "1.0.0"

    private const val PROTOCOL_VERSION = "2.0.0"

    private val POSTGRES_ID: UUID = UUID.fromString("decd338e-5647-4c0b-adf4-da0e75f5a750")
    private val registryBreakingChanges: BreakingChanges =
      BreakingChanges().withAdditionalProperty(
        BREAKING_CHANGE_VERSION,
        VersionBreakingChange()
          .withMessage("Sample message").withUpgradeDeadline("2023-07-20").withMigrationDocumentationUrl("https://example.com"),
      )

    private val SOURCE_POSTGRES: ConnectorRegistrySourceDefinition =
      ConnectorRegistrySourceDefinition()
        .withSourceDefinitionId(POSTGRES_ID)
        .withName("Postgres")
        .withDockerRepository("airbyte/source-postgres")
        .withDockerImageTag(INITIAL_CONNECTOR_VERSION)
        .withDocumentationUrl("https://docs.airbyte.io/integrations/sources/postgres")
        .withSpec(ConnectorSpecification().withProtocolVersion(PROTOCOL_VERSION))
    private val SOURCE_POSTGRES_2: ConnectorRegistrySourceDefinition =
      ConnectorRegistrySourceDefinition()
        .withSourceDefinitionId(POSTGRES_ID)
        .withName("Postgres - Updated")
        .withDockerRepository("airbyte/source-postgres")
        .withDockerImageTag(UPDATED_CONNECTOR_VERSION)
        .withDocumentationUrl("https://docs.airbyte.io/integrations/sources/postgres/new")
        .withSpec(ConnectorSpecification().withProtocolVersion(PROTOCOL_VERSION))
        .withReleases(ConnectorReleases().withBreakingChanges(registryBreakingChanges))

    private val S3_ID: UUID = UUID.fromString("4816b78f-1489-44c1-9060-4b19d5fa9362")
    private val DESTINATION_S3: ConnectorRegistryDestinationDefinition =
      ConnectorRegistryDestinationDefinition()
        .withName("S3")
        .withDestinationDefinitionId(S3_ID)
        .withDockerRepository("airbyte/destination-s3")
        .withDockerImageTag(INITIAL_CONNECTOR_VERSION)
        .withDocumentationUrl("https://docs.airbyte.io/integrations/destinations/s3")
        .withSpec(ConnectorSpecification().withProtocolVersion(PROTOCOL_VERSION))
    private val DESTINATION_S3_2: ConnectorRegistryDestinationDefinition =
      ConnectorRegistryDestinationDefinition()
        .withName("S3 - Updated")
        .withDestinationDefinitionId(S3_ID)
        .withDockerRepository("airbyte/destination-s3")
        .withDockerImageTag(UPDATED_CONNECTOR_VERSION)
        .withDocumentationUrl("https://docs.airbyte.io/integrations/destinations/s3/new")
        .withSpec(ConnectorSpecification().withProtocolVersion(PROTOCOL_VERSION))
        .withReleases(ConnectorReleases().withBreakingChanges(registryBreakingChanges))
  }
}
