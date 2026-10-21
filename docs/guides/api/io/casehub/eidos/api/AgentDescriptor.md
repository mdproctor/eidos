# io.casehub.eidos.api.AgentDescriptor

**Package:** `io.casehub.eidos.api`

**Kind:** `record`

## Fields

### `agentId` (`java.lang.String`)

### `archetype` (`java.lang.String`)

### `archetypeAdjectives` (`java.util.List<java.lang.String>`)

### `avatar` (`java.lang.String`)

### `axisVocabularies` (`java.util.Map<io.casehub.eidos.api.DispositionAxis,java.lang.String>`)

### `briefing` (`java.lang.String`)

### `capabilities` (`java.util.List<io.casehub.eidos.api.AgentCapability>`)

### `constraints` (`java.util.List<io.casehub.eidos.api.AgentConstraint>`)

### `dataHandlingPolicy` (`java.lang.String`)

### `description` (`java.lang.String`)

### `disposition` (`io.casehub.eidos.api.AgentDisposition`)

### `dispositionVocabulary` (`java.lang.String`)

### `domainVocabulary` (`java.lang.String`)

### `extensionData` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `goals` (`java.util.List<io.casehub.eidos.api.AgentGoal>`)

### `jurisdiction` (`java.lang.String`)

### `modelFamily` (`java.lang.String`)

### `modelVersion` (`java.lang.String`)

### `name` (`java.lang.String`)

### `provider` (`java.lang.String`)

### `providerConfigs` (`java.util.List<io.casehub.eidos.api.ProviderConfig>`)

### `slot` (`java.lang.String`)

### `slotVocabulary` (`java.lang.String`)

### `styleVocabulary` (`java.lang.String`)

### `templates` (`java.util.List<io.casehub.eidos.api.TemplateRef>`)

### `tenancyId` (`java.lang.String`)

### `version` (`java.lang.String`)

### `voice` (`io.casehub.eidos.api.AgentVoiceProfile`)

### `weightsFingerprint` (`java.lang.String`)

## Record Components

### `agentId` (`java.lang.String`)

### `archetype` (`java.lang.String`)

### `archetypeAdjectives` (`java.util.List<java.lang.String>`)

### `avatar` (`java.lang.String`)

### `axisVocabularies` (`java.util.Map<io.casehub.eidos.api.DispositionAxis,java.lang.String>`)

### `briefing` (`java.lang.String`)

### `capabilities` (`java.util.List<io.casehub.eidos.api.AgentCapability>`)

### `constraints` (`java.util.List<io.casehub.eidos.api.AgentConstraint>`)

### `dataHandlingPolicy` (`java.lang.String`)

### `description` (`java.lang.String`)

### `disposition` (`io.casehub.eidos.api.AgentDisposition`)

### `dispositionVocabulary` (`java.lang.String`)

### `domainVocabulary` (`java.lang.String`)

### `extensionData` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `goals` (`java.util.List<io.casehub.eidos.api.AgentGoal>`)

### `jurisdiction` (`java.lang.String`)

### `modelFamily` (`java.lang.String`)

### `modelVersion` (`java.lang.String`)

### `name` (`java.lang.String`)

### `provider` (`java.lang.String`)

### `providerConfigs` (`java.util.List<io.casehub.eidos.api.ProviderConfig>`)

### `slot` (`java.lang.String`)

### `slotVocabulary` (`java.lang.String`)

### `styleVocabulary` (`java.lang.String`)

### `templates` (`java.util.List<io.casehub.eidos.api.TemplateRef>`)

### `tenancyId` (`java.lang.String`)

### `version` (`java.lang.String`)

### `voice` (`io.casehub.eidos.api.AgentVoiceProfile`)

### `weightsFingerprint` (`java.lang.String`)

## Constructors

### `public AgentDescriptor(java.lang.String agentId, java.lang.String name, java.lang.String description, java.lang.String version, java.lang.String provider, java.lang.String modelFamily, java.lang.String modelVersion, java.lang.String weightsFingerprint, java.lang.String domainVocabulary, java.lang.String slotVocabulary, java.lang.String dispositionVocabulary, java.lang.String styleVocabulary, java.util.Map<io.casehub.eidos.api.DispositionAxis,java.lang.String> axisVocabularies, java.lang.String slot, java.lang.String archetype, java.util.List<java.lang.String> archetypeAdjectives, java.lang.String avatar, java.util.List<io.casehub.eidos.api.AgentCapability> capabilities, io.casehub.eidos.api.AgentDisposition disposition, java.lang.String jurisdiction, java.lang.String dataHandlingPolicy, java.lang.String tenancyId, java.lang.String briefing, io.casehub.eidos.api.AgentVoiceProfile voice, java.util.List<io.casehub.eidos.api.TemplateRef> templates, java.util.List<io.casehub.eidos.api.AgentGoal> goals, java.util.List<io.casehub.eidos.api.AgentConstraint> constraints, java.util.List<io.casehub.eidos.api.ProviderConfig> providerConfigs, java.util.Map<java.lang.String,java.lang.Object> extensionData)`

#### Parameters

- `agentId` (`java.lang.String`)
- `name` (`java.lang.String`)
- `description` (`java.lang.String`)
- `version` (`java.lang.String`)
- `provider` (`java.lang.String`)
- `modelFamily` (`java.lang.String`)
- `modelVersion` (`java.lang.String`)
- `weightsFingerprint` (`java.lang.String`)
- `domainVocabulary` (`java.lang.String`)
- `slotVocabulary` (`java.lang.String`)
- `dispositionVocabulary` (`java.lang.String`)
- `styleVocabulary` (`java.lang.String`)
- `axisVocabularies` (`java.util.Map<io.casehub.eidos.api.DispositionAxis,java.lang.String>`)
- `slot` (`java.lang.String`)
- `archetype` (`java.lang.String`)
- `archetypeAdjectives` (`java.util.List<java.lang.String>`)
- `avatar` (`java.lang.String`)
- `capabilities` (`java.util.List<io.casehub.eidos.api.AgentCapability>`)
- `disposition` (`io.casehub.eidos.api.AgentDisposition`)
- `jurisdiction` (`java.lang.String`)
- `dataHandlingPolicy` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `briefing` (`java.lang.String`)
- `voice` (`io.casehub.eidos.api.AgentVoiceProfile`)
- `templates` (`java.util.List<io.casehub.eidos.api.TemplateRef>`)
- `goals` (`java.util.List<io.casehub.eidos.api.AgentGoal>`)
- `constraints` (`java.util.List<io.casehub.eidos.api.AgentConstraint>`)
- `providerConfigs` (`java.util.List<io.casehub.eidos.api.ProviderConfig>`)
- `extensionData` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public java.lang.String agentId()`

### `public java.lang.String archetype()`

### `public java.util.List<java.lang.String> archetypeAdjectives()`

### `public java.lang.String avatar()`

### `public java.util.Map<io.casehub.eidos.api.DispositionAxis,java.lang.String> axisVocabularies()`

### `public java.lang.String briefing()`

### `public static io.casehub.eidos.api.AgentDescriptor.Builder builder()`

### `public java.util.List<io.casehub.eidos.api.AgentCapability> capabilities()`

### `public java.util.List<io.casehub.eidos.api.AgentConstraint> constraints()`

### `public java.lang.String dataHandlingPolicy()`

### `public java.lang.String description()`

### `public io.casehub.eidos.api.AgentDisposition disposition()`

### `public java.lang.String dispositionVocabulary()`

### `public java.lang.String domainVocabulary()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.lang.Object> extensionData()`

### `public java.util.List<io.casehub.eidos.api.AgentGoal> goals()`

### `public boolean hasConstraint(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public boolean hasGoal(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public final int hashCode()`

### `public java.lang.String jurisdiction()`

### `public java.lang.String modelFamily()`

### `public java.lang.String modelVersion()`

### `public java.lang.String name()`

### `public java.lang.String provider()`

### `public java.util.List<io.casehub.eidos.api.ProviderConfig> providerConfigs()`

### `public java.util.List<io.casehub.eidos.api.AgentConstraint> publicConstraints()`

### `public java.util.List<io.casehub.eidos.api.AgentGoal> publicGoals()`

### `public java.lang.String slot()`

### `public java.lang.String slotVocabulary()`

### `public java.lang.String styleVocabulary()`

### `public java.util.List<io.casehub.eidos.api.TemplateRef> templates()`

### `public java.lang.String tenancyId()`

### `public io.casehub.eidos.api.AgentDescriptor.Builder toBuilder()`

### `public final java.lang.String toString()`

### `public java.lang.String version()`

### `public java.util.Optional<java.lang.String> vocabUriForAxis(io.casehub.eidos.api.DispositionAxis axis)`

#### Parameters

- `axis` (`io.casehub.eidos.api.DispositionAxis`)

### `public java.util.Optional<java.lang.String> vocabUriForSlot()`

### `public io.casehub.eidos.api.AgentVoiceProfile voice()`

### `public java.lang.String weightsFingerprint()`
