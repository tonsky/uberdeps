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
{:deps {uberdeps/uberdeps {:mvn/version "1.1.4"}}}
```

uberdeps/package.sh:

```sh
#!/bin/bash -e
cd "$( dirname "${BASH_SOURCE[0]}" )"
clojure -M -m uberdeps.uberjar --deps-file ../deps.edn --target ../target/project.jar
```

To be clear:

- `/uberdeps/deps.edn` is used only to start uberdeps. Files, paths, profiles from it won’t affect resulting archive in any way.
- `/deps.edn` (referred as `--deps-file ../deps.edn` from `/uberdeps/package.sh`) is what’s analyzed during packaging. Its content determines what goes into the final archive. 

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
      :extra-deps {nrepl/nrepl {:mvn/version "0.6.0"}}
    }
  }
}
```

uberdeps/package.sh:

```sh
#!/bin/bash -e
cd "$( dirname "${BASH_SOURCE[0]}" )"
clojure -M -m uberdeps.uberjar --deps-file ../deps.edn --target ../target/project.jar --aliases package:nrepl:...
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
      :replace-deps {uberdeps/uberdeps {:mvn/version "1.1.4"}}
      :replace-paths []
      :main-opts ["-m" "uberdeps.uberjar"]
    }
  }
}
```

and invoke it like this (requires clj >= 1.10.1.672):

```sh
clj -M:uberdeps
```

In that case execution will happen like this:

1. JVM will start with `:uberdeps` alias which will REPLACE all your normal dependencies on your app’s classpath with `uberdeps` dependency.
2. `uberdeps.uberjar` namespace will be invoked as main namespace.
3. Uberdeps process will read `deps.edn` AGAIN, this time figuring out what should go into archive. Note again, it doesn’t matter what’s on classpath of Uberdeps process. What matters is what it reads from `deps.edn` itself. Archive will not inherit any profiles enabled during execution, or any classpath resources, meaning, for example, that uberdeps won’t package its own classes to archive.
4. Final archive is created, JVM exits.

## No-config setup

You can invoke Uberdeps from command line at any time without any prior setup.

Add to your bash aliases:

```sh
clj -Sdeps '{:aliases {:uberjar {:replace-deps {uberdeps/uberdeps {:mvn/version "1.1.4"}} :replace-paths []}}}' -M:uberjar -m uberdeps.uberjar
```

Or add to your `~/.clojure/deps.edn`:

```clojure
:aliases {
  :uberjar {:replace-deps {uberdeps/uberdeps {:mvn/version "1.1.4"}}
            :replace-paths []
            :main-opts ["-m" "uberdeps.uberjar"]}
}
```

Both of these method will replace whatever is in your `deps.edn` with `uberdeps`, so at runtime it is an exact equivalent of “Quick and dirty” setup.

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

Make sure you have Clojure as a dependency—uberdeps won’t automatically add it for you:

{:deps {org.clojure/clojure {:mvn/version "1.10.0"}, ...}}

# 3. Aot compile
clj -M -e "(compile 'app.core)"

# 4. Uberjar with --main-class option
clojure -M:uberjar --main-class app.core
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
--exclude <regexp>                Exclude files that match one or more of the Regular Expression given. Can be used multiple times
--main-class <ns>                 Main class, if it exists (e.g. app.core)
--multi-release                   Add Multi-Release: true to the manifest. Off by default.
--level (debug|info|warn|error)   Verbose level. Defaults to debug
```

## Programmatic API

```clojure
(require '[uberdeps.api :as uberdeps])

(let [exclusions [#"\.DS_Store" #".*\.cljs" #"cljsjs/.*"]
      deps       (clojure.edn/read-string (slurp "deps.edn"))]
  (binding [uberdeps/level      :warn]
    (uberdeps/package deps "target/uber.jar" {:aliases #{:uberjar}
                                              :exclusions exclusions})))
```

## Merging

Sometimes assembling uberjar requires combining multiple files with the same name (coming from different libraries, for example) into a single file. Uberdeps does that automatically for these:

```clojure
META-INF/plexus/components.xml uberdeps.api/components-merger
#"META-INF/services/.*"        uberdeps.api/services-merger   
#"data_readers.clj[cs]?"       uberdeps.api/clojure-maps-merger
```

You can provide your own merger by passing a merge function to `uberdeps.api/package`:

```clojure
(def readme-merger
  {:collect
   (fn [acc content]
     (conj (or acc []) (str/upper-case content)))
   :combine
   (fn [acc]
     (str/join "\n" acc))})
```

Merger is a map with two keys: `:collect` and `:combine`. Collect accumulates values as they come. It takes an accumulator and a next file content, and must return the new accumulator:

```
((:collect readme-merger) acc content) -> acc'
```

File content is always a string. Accumulator can be any data structure you find useful for storing merged files. In `readme-merger` accumulator is a string, in `clojure-maps-merger` it is a clojure map, etc. On a first  call to your merger accumulator will be `nil`.

Combine is called when all files with the same name have been processed and it is time to write the resulting single merged file to the jar. It will be called with your accumulator and must return a string with file content:

```
((:combine readme-merger) acc) -> content'
```

Custom mergers can be passed to `uberdeps.api/package` in `:mergers` option along with file path regexp:

```
(uberdeps.api/package
  deps
  "target/project.jar"
  {:mergers {#"(?i)README(\.md|\.txt)?" readme-merger}})
```

Passing custom mergers does not remove the default ones, but you can override them.

## License

Copyright © 2019 Nikita Prokopov.

Licensed under MIT (see [LICENSE](LICENSE)).
