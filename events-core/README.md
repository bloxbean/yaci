# Yaci Events Core

This module provides the framework‑agnostic event SPI used by Yaci Node and embedders:

- SPI: `Event`, `EventBus`, `EventListener`, `EventContext`, `EventMetadata`, `SubscriptionOptions`, `PublishOptions`, `@DomainEventListener`.
- Implementations: `SimpleEventBus` (default), `NoopEventBus` (disabled delivery).
- Build‑time SPI: `support.DomainEventBindings` discovered via `ServiceLoader` for GraalVM‑friendly listener binding.
- Annotation registrar: prefers generated bindings; falls back to reflection when none are present.

For a practical, end‑to‑end guide on using events and plugins (including the annotation processor, plugin SPI, and publication points), see:

- ../node-runtime/docs/events-and-plugins-guide.md

