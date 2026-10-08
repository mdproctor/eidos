# io.casehub.eidos.api.PromptBlock

**Package:** `io.casehub.eidos.api`

**Kind:** `record`

## Fields

### `content` (`java.lang.String`)

### `salience` (`float`)

### `tag` (`java.lang.String`)

### `tier` (`io.casehub.eidos.api.PromptTier`)

## Record Components

### `content` (`java.lang.String`)

### `salience` (`float`)

### `tag` (`java.lang.String`)

### `tier` (`io.casehub.eidos.api.PromptTier`)

## Constructors

### `public PromptBlock(io.casehub.eidos.api.PromptTier tier, java.lang.String tag, float salience, java.lang.String content)`

#### Parameters

- `tier` (`io.casehub.eidos.api.PromptTier`)
- `tag` (`java.lang.String`)
- `salience` (`float`)
- `content` (`java.lang.String`)

## Methods

### `public static io.casehub.eidos.api.PromptBlock cognitive(java.lang.String tag, java.lang.String content)`

#### Parameters

- `tag` (`java.lang.String`)
- `content` (`java.lang.String`)

### `public static io.casehub.eidos.api.PromptBlock command(java.lang.String content)`

#### Parameters

- `content` (`java.lang.String`)

### `public java.lang.String content()`

### `public static io.casehub.eidos.api.PromptBlock conversation(java.lang.String content)`

#### Parameters

- `content` (`java.lang.String`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public static io.casehub.eidos.api.PromptBlock identity(java.lang.String content)`

#### Parameters

- `content` (`java.lang.String`)

### `public float salience()`

### `public java.lang.String tag()`

### `public io.casehub.eidos.api.PromptTier tier()`

### `public final java.lang.String toString()`
