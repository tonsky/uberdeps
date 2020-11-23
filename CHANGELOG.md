### 1.0.3 - Nov 23, 2020

- Fix relative paths issue when deps.edn is a symlink #35 thx @imrekoszo
- tools.deps updated to 0.9.833

### 1.0.2 - Sep 16, 2020

- Exclude Emacs backup files by default #30

### 1.0.1 - Sep 10, 2020

- Removed `(set! *print-namespace-maps*)` from code #29
- tools.deps updated to 0.9.782

### 1.0.0 - Aug 19, 2020

- Automatically merge `data_readers.clj` and `META-INF/plexus/components.xml`
- Support custom mergers #1
- tools.deps updated to 0.9.763

### 0.1.11 - Aug 5, 2020

- Concat all `META-INF/services/**` files with matching names #2 #27 thx @jdf-id-au

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
