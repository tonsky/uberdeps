# Uberjar builder for deps.edn projects

Takes deps.edn and packs an uberjar out of it.

- Fast: Does not unpack intermediate jars on disk.
- Explicit: Prints dependency tree. Realize how much crap you’re packing.
- Standalone: does not depend on current classpath, does not need live Clojure environment.
- Embeddable and configurable: fine-tune your build by combining config options and calling specific steps from your code.

## Rationale

It is important to be aware that build classpath is not the same as your runtime classpath. What you need to build your app is usually not what you need to run it. For example, build classpath can contain uberdeps, tools.deps.alpha, ClojureScript compiler etc. Your production application can happily run without all that! Build classpath does not need your app dependencies. When you just packing files into a jar you don’t not need something monumental like Datomic or nREPL loaded!

Other build systems sometimes do not make a strict distinction here. It’s not uncommon to e.g. see ClojureScript dependency in `project.clj` in main profile.

Uberdeps is different from other archivers in that it strictly separates the two. It works roughly as follows:

1. JVM with a single `uberdeps` dependency is started (NOT on the app’s classpath).
2. It reads your app’s `deps.edn` and figures out from it which jars, files and dirs it should package. Your app or your app’s dependencies are not loaded! Just their dependencies are analyzed, using `tools.deps.alpha` as a runtime library. 
3. Final archive is created, JVM exits.

## Project setup

Ideally you would setup a separate `deps.edn` for packaging:

```
project
├ deps.edn
├ src
├ ...
└ uberdeps
  ├ deps.edn
  └ package.sh
```

with following content:

uberdeps/deps.edn:

```clojure
{:deps {uberdeps {:mvn/version "0.1.10"}}}
```

uberdeps/package.sh:

```sh
#!/bin/bash -e
cd "$( dirname "${BASH_SOURCE[0]}" )"
clojure -m uberdeps.uberjar --deps-file ../deps.edn --target ../target/project.jar
```

To be clear:

- `/uberdeps/deps.edn` is used only to start uberdeps. Files, paths, profiles from it won’t affect resulting archive in any way.
- `/deps.edn` (referred as `--deps-file ../deps.edn` from `/uberdeps/packages.h`) is what’s analyzed during packaging. Its content determines what goes into the final archive. 

In an ideal world, I’d prefer to have `/uberdeps.edn` next to `/deps.edn` in a top-level dir instead of `/uberdeps/deps.edn`. Unfortunately, `clj` / `clojure` bash scripts do not allow overriding `deps.edn` file path with anything else, that’s why extra `uberdeps` directory is needed.

## Project setup — extra aliases

You CAN use aliases to control what goes into resulting archive, just as you would with normal `deps.edn`. Just remember to tell `uberdeps` about it with `--aliases` option:

deps.edn:

```clojure
{ :paths ["src"]
  ...
  :aliases {
    :package {
      :extra-paths ["resources" "target/cljs/"]
    }
    :nrepl {
      :extra-deps {nrepl {:mvn/version "0.6.0"}}
    }
  }
}
```

uberdeps/package.sh:

```sh
#!/bin/bash -e
cd "$( dirname "${BASH_SOURCE[0]}" )"
clojure -m uberdeps.uberjar --deps-file ../deps.edn --target ../target/project.jar --aliases package:nrepl:...
```

## Project setup — quick and dirty

Sometimes it’s just too much setup to have an extra script and extra `deps.edn` file just to run simple archiver. In that case you can add `uberdeps` in your main `deps.edn` under an alias. This will mean your app’s classpath will load during packaging, which is extra work but should make no harm.

```
project
├ deps.edn
├ src
└ ...
```

deps.edn:

```clojure
{ :paths ["src"]
  ...
  :aliases {
    :uberdeps {
      :extra-deps {uberdeps {:mvn/version "0.1.10"}}
      :main-opts ["-m" "uberdeps.uberjar"]
    }
  }
}
```

and invoke it like this:

```sh
clj -A:uberdeps
```

In that case execution will happen like this:

1. JVM will start with `:uberdeps` alias which will ADD `uberdeps` dependency to your app’s classpath.
2. `uberdeps.uberjar` namespace will be invoked as main namespace.
3. Uberdeps process will read `deps.edn` AGAIN, this time figuring out what should go into archive. Note again, it doesn’t matter what’s on classpath of Uberdeps process. What matters is what it reads from `deps.edn` itself. Archive will not inherit any profiles enabled during execution, or any classpath resources, meaning, for example, that uberdeps won’t package its own classes to archive.
4. Final archive is created, JVM exits.

## No-config setup

You can invoke Uberdeps from command line at any time without any prior setup.

Add to your bash aliases:

```sh
clj -Sdeps '{:deps {uberdeps {:mvn/version "0.1.10"}}}' -m uberdeps.uberjar
```

Or add to your `~/.clojure/deps.edn`:

```clojure
:aliases {
  :uberjar {:extra-deps {uberdeps {:mvn/version "0.1.10"}}
            :main-opts ["-m" "uberdeps.uberjar"]}
}
```

Both of these method will merge `uberdeps` with whatever is in your `deps.edn`, so at runtime it is an exact equivalent of “Quick and dirty” setup.

## Using the generated uberjar

If your project has a `-main` function, you can run it from within the generated uberjar:

```sh
java -cp target/<your-project>.jar clojure.main -m <your-namespace-with-main>
```

## Creating an executable jar

Given your project has a `-main` function like below:

```clojure
(ns app.core
  (:gen-class))

(defn -main [& args]
      (println "Hello world"))
```

You can create an executable jar with these steps:

```sh
# 1. Ensure dir exists
mkdir classes

# 2. Add `classes` dir to the classpath in `deps.edn`:
{:paths [... "classes"]}

# 3. Aot compile
clj -e "(compile 'app.core)"

# 4. Uberjar with --main-class option
clojure -A:uberjar --main-class app.core
```

This will create a manifest in the jar under META-INF/MANIFEST.MF, which then allows you to run your jar directly:

```sh
java -jar target/<your-project>.jar
```

For more information on AOT compiling in tools.deps, have a look [at the official guide](https://clojure.org/guides/deps_and_cli#aot_compilation).

## Command-line options

Supported command-line options are:

```
--deps-file <file>                Which deps.edn file to use to build classpath. Defaults to 'deps.edn'
--aliases <alias:alias:...>       Colon-separated list of alias names to include from deps file. Defaults to nothing
--target <file>                   Jar file to ouput to. Defaults to 'target/<directory-name>.jar'
--main-class <ns>                 Main class, if it exists (e.g. app.core)
--multi-release                   Add Multi-Release: true to the manifest. Off by default.
--level (debug|info|warn|error)   Verbose level. Defaults to debug
```

## Programmatic API

```clojure
(require '[uberdeps.api :as uberdeps])

(let [exclusions (into uberdeps/exclusions [#"\.DS_Store" #".*\.cljs" #"cljsjs/.*"])
      deps       (clojure.edn/read-string (slurp "deps.edn"))]
  (binding [uberdeps/exclusions exclusions
            uberdeps/level      :warn]
    (uberdeps/package deps "target/uber.jar" {:aliases #{:uberjar}})))
```

## Changelog

### 0.1.10 - Mar 31, 2020

- tools.deps updated to 0.8.677

### 0.1.9 - Mar 31, 2020

- `--multi-release` / `:multi-release?` added #22 #23 thx @gavinkflam

### 0.1.8 - Jan 13, 2020

- Resolve `:paths` relative to `deps.edn` location

### 0.1.7 - Dec 20, 2019

- `--main-class` / `:main-class` option added #13 #18 thx @gnarroway
- tools.deps updated to 0.8.599

### 0.1.6 - Oct 4, 2019

- Replace `\` with `/` when building on Windows #16 #17 thx @gnarroway

### 0.1.5 - Oct 4, 2019

- Ignore non-jar dependencies #14 #15

### 0.1.4 - June 5, 2019

- Package paths before jars so that local files take priority over deps #9

### 0.1.3 - May 30, 2019

- Fixed NPE when target is located in current dir #7

### 0.1.2 - May 27, 2019

- Make target dirs if don’t exist #4

### 0.1.1 - May 3, 2019

- Normalize dependencies without namespaces #3

### 0.1.0 - April 29, 2019

- Initial version

## License

Copyright © 2019 Nikita Prokopov.

Licensed under MIT (see [LICENSE](LICENSE)).
