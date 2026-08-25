# Retry Control Room frontend

This is the supplied React 19/Vite shell for the Safe Retry assignment. Task loading, selection, responsive presentation, and fake bearer authentication are already wired.

## Commands

```bash
npm ci
npm test
npm run test:coverage
npm run build
npm run dev
```

The development proxy sends `/api` requests to `http://backend:8080`. The supplied exercise token defaults to `tenant-alpha-token` and may be changed with `VITE_DEMO_AUTH_TOKEN`. It represents a fake authenticated server context, not a production authentication design.

## Candidate-owned work

Search for `TODO(candidate)` and complete the retry workflow. Your implementation must satisfy the published assignment contract for the retry API, idempotency key, expected task version, pending state, success/error/conflict feedback, and protection against stale responses. Convert the pending public contract tests into executable tests and add any cases needed to establish confidence.

Do not send a tenant ID from the browser. The backend derives tenant identity from the supplied authentication context.
