# Ingestion Progress Indicator Design

## Goal

Show console progress while document chunks are embedded and written to the vector store during startup ingestion.

## Design

`RagIngestorService` already creates the complete chunk list before calling `VectorStore.add`. Keep that ordering so the total is known, then split the list into fixed-size batches and call `VectorStore.add` once per batch. After each successful call, update a terminal progress bar with the number of chunks written and the total. Display progress per ingested source file, for both PDF and Slack ingestion.

The progress unit is a successfully completed batch, not internal embedding or SQL work: `VectorStore.add` is a synchronous, opaque operation to this service. If a batch fails, let the existing exception flow report the failure and do not count that batch as complete. Empty input should not divide by zero or produce a misleading percentage. Keep the implementation dependency-free and do not add persistent job state or an HTTP progress API.

## Scope and compatibility

- Preserve chunk contents, metadata, IDs, and ordering.
- Keep ingestion sequential as it is today.
- Use a fixed batch size of 100 chunks; each batch is sent to the existing `VectorStore`.
- Render a 30-character bar and completed/total chunk counts to standard output, then terminate the progress line cleanly when ingestion finishes.

## Verification

- Unit tests verify that all chunks are sent in order across multiple batches and that progress reaches completion only after successful writes.
- Cover empty input and a failing batch so progress does not report unpersisted chunks.
- Run the focused `RagIngestorServiceTest` suite.
