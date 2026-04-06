---
layout: page
title: Local Development Guide
permalink: /dev/
---

# Local Development Guide

This guide explains how to set up and test the SECMAN blog locally using Jekyll.

## Prerequisites

- **Ruby** 3.0+ with `bundler` gem installed
- **Git** for version control

Check your Ruby version:

```bash
ruby --version
gem install bundler
```

## Setup

1. Navigate to the blog directory:

   ```bash
   cd gh-pages
   ```

2. Install dependencies:

   ```bash
   bundle install
   ```

   This installs Jekyll and all plugins defined in the `Gemfile`.

## Running Locally

Start the development server with live reload:

```bash
bundle exec jekyll serve --livereload
```

The site will be available at **[http://localhost:4000/secman/](http://localhost:4000/secman/)**.

Jekyll watches for file changes and rebuilds automatically. The `--livereload` flag refreshes your browser on each rebuild.

### Useful serve options

| Flag | Purpose |
|------|---------|
| `--livereload` | Auto-refresh browser on changes |
| `--drafts` | Include posts in the `_drafts/` folder |
| `--port 5000` | Use a custom port |
| `--host 0.0.0.0` | Expose to your local network |
| `--incremental` | Faster rebuilds (only changed files) |

Example with multiple flags:

```bash
bundle exec jekyll serve --livereload --drafts --incremental
```

## Building Without Serving

To generate the static site without starting a server:

```bash
bundle exec jekyll build
```

Output goes to the `_site/` directory. You can inspect the generated HTML there or serve it with any static file server.

## Writing Blog Posts

Create a new Markdown file in `_posts/` with the naming convention:

```
_posts/YYYY-MM-DD-title-slug.md
```

Every post needs front matter at the top:

```yaml
---
layout: post
title: "Your Post Title"
date: 2026-04-06
category: Security
tags: [vulnerability, risk, management]
author: SECMAN Team
---

Your content in Markdown here.
```

### Working with Drafts

Put work-in-progress posts in `_drafts/` (without a date prefix):

```
_drafts/my-upcoming-post.md
```

View drafts locally with:

```bash
bundle exec jekyll serve --drafts
```

Drafts are **not** included in production builds.

## Directory Structure

```
gh-pages/
├── _config.yml          # Site configuration
├── Gemfile              # Ruby dependencies
├── index.html           # Home page (paginated post list)
├── about.md             # About page
├── _layouts/            # Page templates
│   ├── default.html     #   Base layout (head, header, footer)
│   ├── home.html        #   Home with hero + post grid
│   ├── post.html        #   Single blog post
│   └── page.html        #   Static page
├── _includes/           # Reusable partials
│   ├── head.html        #   <head> meta and CSS
│   ├── header.html      #   Top navigation bar
│   └── footer.html      #   Site footer
├── _sass/               # SCSS theme (secman design)
│   ├── _variables.scss  #   Colors, fonts, spacing
│   ├── _base.scss       #   Element styles
│   └── _layout.scss     #   Component styles
├── _posts/              # Published blog posts
├── assets/
│   ├── css/main.scss    # SCSS entry point
│   └── images/          # Logo and images
└── _site/               # Generated output (git-ignored)
```

## Customization

### Colors and Theme

All design tokens are in `_sass/_variables.scss`. The theme uses the secman Scandinavian design palette:

- **Primary**: `#4A7C6F` (teal-sage)
- **Dark header**: `#3D4F4F`
- **Accent**: `#6B9E9E` (seafoam)
- **Background**: `#F8F9FA`

### Adding a New Page

Create a Markdown file at the root with front matter:

```yaml
---
layout: page
title: My Page
permalink: /my-page/
---
```

Then add a link to `_includes/header.html` if you want it in the navigation.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `bundle exec` not found | Run `gem install bundler` |
| Port already in use | Use `--port 5000` or stop the other process |
| Styles not updating | Delete `_site/` and rebuild: `rm -rf _site && bundle exec jekyll serve` |
| Sass errors | Check `_sass/_variables.scss` for missing semicolons |
| Pagination not working | Ensure `index.html` (not `index.md`) is used for the home page |
