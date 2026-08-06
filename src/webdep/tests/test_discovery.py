from webdep.discovery import describe_dependency, discover_dependencies, normalize_uris


def test_discover_dependencies_resolves_and_deduplicates_urls():
    html = """
    <html><head>
      <link rel="stylesheet" href="/static/bootstrap-5.3.2.min.css">
      <link rel="preload" as="script" href="https://cdn.jsdelivr.net/npm/react@19.1.0/umd/react.production.min.js">
      <script src="/static/jquery-3.7.1.min.js"></script>
      <script src="/static/jquery-3.7.1.min.js"></script>
    </head></html>
    """

    dependencies = discover_dependencies(html, "https://example.test/app/index.html")

    assert [dependency.name for dependency in dependencies] == ["bootstrap", "react", "jquery"]
    assert [dependency.version for dependency in dependencies] == ["5.3.2", "19.1.0", "3.7.1"]
    assert dependencies[0].url == "https://example.test/static/bootstrap-5.3.2.min.css"
    assert dependencies[1].category == "JavaScript library"


def test_describe_dependency_handles_common_cdn_layouts():
    dependency = describe_dependency(
        "https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.21/lodash.min.js",
        "JavaScript library",
    )

    assert dependency.name == "lodash.js"
    assert dependency.vendor == "cdnjs"
    assert dependency.version == "4.17.21"


def test_normalize_uris_filters_non_http_and_forces_root_path():
    assert normalize_uris(["example.test/app", "urn:asset:1", "# comment", "https://example.test/other?q=1"]) == [
        "https://example.test/",
    ]
