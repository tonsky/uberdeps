# Uberjar builder for deps.edn projects

Takes deps.edn and packs an uberjar out of it.

- Fast: Does not unpack intermediate jars on disk.
- Explicit: Prints dependency tree. Realize how much crap you’re packing.
- Standalone: does not depend on current classpath, does not need live Clojure environment.
- Embeddable and configurable: fine-tune your build by combining config options and calling specific steps from your code.

## Usage

Add to your bash aliases:

```sh
clj -Sdeps '{:deps {uberdeps {:mvn/version "0.1.7"}}}' -m uberdeps.uberjar
```

Or add to your `deps.edn` or `~/.clojure/deps.edn`:

```clojure
:aliases {
  :uberjar {:extra-deps {uberdeps {:mvn/version "0.1.7"}}
            :main-opts ["-m" "uberdeps.uberjar"]}
}
```

Supported command-line options are:

```
--deps-file <file>                Which deps.edn file to use to build classpath. Defaults to 'deps.edn'
--aliases <alias:alias:...>       Colon-separated list of alias names to include from deps file. Defaults to nothing
--target <file>                   Jar file to ouput to. Defaults to 'target/<directory-name>.jar'
--main-class <ns>                 Main class, if it exists (e.g. app.core)
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

## Using the generated uberjar

If your project has a `-main` function, you can run it from within the generated uberjar:

```
java -cp target/<your project>.jar clojure.main -m <your namespace with main>
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

```bash
# ensure dir exists
mkdir classes

# aot compile
clj -e "(compile 'app.core)"

# uberjar with --main-class option
clojure -A:uberjar --main-class app.core
```

This will create a manifest in the jar under META-INF/MANIFEST.MF, which then allows you to run your jar directly:

```
java -jar target/<your-project>.jar
```

For more information on AOT compiling in tools.deps, have a look at the official [guide](https://clojure.org/guides/deps_and_cli#aot_compilation).

## Changelog

### 0.1.7 - Dec 20, 2019

- --main-class / :main-class option added #13 #18 thx @gnarroway
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

Copyright © 2019 Nikita Prokopov

Licensed under MIT (see [LICENSE](LICENSE)).
