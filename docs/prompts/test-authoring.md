# Test authoring rules

Tests verify the specification, not the implementation.

Sources, in order: `README.md`, `docs/ai-state/DECISIONS.md` (when a decision
applies), public Javadoc. Write the test from the spec before reading the
code under test.

## Rules

- R1: Name the observable TDM behavior, not the method
  (`capturedDatasetPreservesConfiguredTableOrder`).
- R2: No access to private or package-private internals (no reflection); use
  public interfaces or the CLI.
- R3: Each test cites its spec source in a comment (`// Spec: README.md > ...`).
- R4: Body uses `// Given` / `// When` / `// Then`.
- R5: No assertions on internal structures; assert observable effects.
- R6: At least one adversarial case per behavior (empty dataset, invalid
  configuration, missing file, secret leaked to logs, partial failure).
- R7: Do not mock the code under test. Build real inputs in a JUnit `@TempDir`.
  Mock only the database layer, following the engine contract.

## Checklist

- Name describes domain behavior?
- Spec source cited?
- Only public interfaces used?
- Adversarial case present?
- Which code change would make it fail? If none, the test is worthless.
