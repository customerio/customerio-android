# Documentation Quick Reference

Quick commands and links for working with API documentation.

## 🚀 Quick Commands

```bash
# Generate docs locally
make docs

# View docs
open build/dokka/htmlMultiModule/index.html

# Clean docs
make docs-clean

# Force fresh generation
make docs-clean && make docs
```

## 🔗 Published Documentation URLs

Once GitHub Pages is set up:

- **Latest (stable):** https://customerio.github.io/customerio-android/latest/
- **All versions:** https://customerio.github.io/customerio-android/
- **Specific version:** https://customerio.github.io/customerio-android/3.0.0/
- **Home:** https://customerio.github.io/customerio-android/

## 📦 Publishing to GitHub Pages

### Automatic (on release)
```bash
# Tag and push triggers automatic publishing
git tag 3.0.0
git push origin 3.0.0
# ✅ Docs auto-publish in 3-5 minutes
```

### Manual
1. Go to: https://github.com/customerio/customerio-android/actions
2. Click "Publish API Documentation"
3. Click "Run workflow"
4. Enter version (e.g., `3.0.0`) and ref (e.g., `main`)
5. Click "Run workflow"
6. Wait ~5 minutes
7. View at: https://customerio.github.io/customerio-android/{version}/

## 🔧 Configuration Files

| File | Purpose |
|------|---------|
| `build.gradle` | Dokka configuration, exclusion rules |
| `Makefile` | `make docs` command |
| `.github/workflows/publish-api-docs.yml` | GitHub Pages publishing |
| `.github/workflows/generate-docs-manual.yml` | Manual doc generation (no GitHub Pages) |
| `.dokka/custom-styles.css` | Optional custom styling |

## 🎯 What Gets Documented

✅ **Included:**
- Public classes, methods, properties
- Classes with KDoc comments
- All modules: core, datapipelines, messagingpush, messaginginapp

❌ **Excluded:**
- `internal` visibility
- `private` visibility  
- `protected` visibility
- Packages named `*.internal.*`
- Empty packages

## 📝 For Technical Writers

**Generate docs without Android Studio:**

See: [`docs/dev-notes/GENERATING-DOCS-FOR-WRITERS.md`](GENERATING-DOCS-FOR-WRITERS.md)

**Options:**
1. Minimal command-line tools (~500MB)
2. GitHub Actions (zero local setup)

## 🔒 Hiding Internal APIs

Mark classes/methods as internal:

```kotlin
// Option 1: Use internal visibility
internal class InternalHelper { }

// Option 2: Place in .internal package
package io.customer.sdk.internal

// Option 3: Combine annotation + visibility
@InternalCustomerIOApi
internal fun internalMethod() { }
```

See: [`docs/dev-notes/DOCUMENTATION-SUPPRESSION.md`](DOCUMENTATION-SUPPRESSION.md)

## 🆘 Troubleshooting

### Docs won't generate
```bash
# Check Android SDK is configured
cat local.properties
# Should show: sdk.dir=/path/to/android-sdk

# Try with verbose output
./gradlew generateDocs --info
```

### GitHub Pages 404
1. Enable Pages: Settings → Pages → Source: gh-pages / (root)
2. Check workflow succeeded: Actions tab
3. Wait 1-2 minutes after first run

### Internal classes appear in docs
1. Mark with `internal` visibility
2. Move to `*.internal.*` package
3. See: `docs/dev-notes/DOCUMENTATION-SUPPRESSION.md`

### `make docs` says "up to date"
- The `docs/` directory confuses Make
- Fixed with `.PHONY: docs` in Makefile
- Run: `make docs-clean && make docs`

## 📚 Full Documentation

| Guide | Description |
|-------|-------------|
| [DOCUMENTATION-SETUP.md](../../DOCUMENTATION-SETUP.md) | Quick start and overview |
| [GENERATING-DOCS-FOR-WRITERS.md](GENERATING-DOCS-FOR-WRITERS.md) | For non-developers |
| [API-DOCUMENTATION.md](API-DOCUMENTATION.md) | Complete guide |
| [DOCUMENTATION-SUPPRESSION.md](DOCUMENTATION-SUPPRESSION.md) | Hiding internal APIs |
| [GITHUB-PAGES-SETUP.md](GITHUB-PAGES-SETUP.md) | Publishing to GitHub Pages |

## 🎓 Learning Resources

- [Dokka Documentation](https://kotlinlang.org/docs/dokka-introduction.html)
- [KDoc Syntax](https://kotlinlang.org/docs/kotlin-doc.html)
- [GitHub Pages](https://docs.github.com/en/pages)

## ✅ Checklist: First-Time Setup

- [ ] Install Android SDK (or use GitHub Actions)
- [ ] Generate docs locally: `make docs`
- [ ] Review output, fix any issues
- [ ] Enable GitHub Pages: Settings → Pages
- [ ] Commit workflow: `.github/workflows/publish-api-docs.yml`
- [ ] Test: Push a tag or run workflow manually
- [ ] Update main docs site to link to API reference
- [ ] Add link to README.md

## 🎉 You're Done When...

- ✅ `make docs` generates without errors
- ✅ Internal APIs are hidden
- ✅ GitHub Pages is live
- ✅ Docs auto-publish on release
- ✅ Main docs site links to API reference


