## 0.0-3126

### Fixes
* Need to wrap REPL -setup calls in cljs.compiler/with-core-cljs

## 0.0-3123

### Fixes
* CLJS-1131: cljs.closure/add-dependencies needs to be more aggressively set oriented
* CLJS-1132: compile-file analysis pass optimization broken under Closure optimization and :cache-analysis true

## 0.0-3119

### Fixes
* CLJS-1130: :foreign-libs regression under Closure optimized builds

## 0.0-3117

### Fixes
* CLJS-1126: File are not recompiled when build affecting options changes

## 0.0-3115

### Enhancements
* CLJS-806: support ^:const
* CLJS-1115: Reusable repl-bootstrap! fn

### Changes
* CLJS-667: validate extend-type and extend-protocol shape
* CLJS-1112: :repl-requires option for REPL evaluation environment
* CLJS-1111: browser REPL should have no side effects until -setup

### Fixes
* CLJS-1085: Allow to pass test environment to cljs.test/run-all-tests
* CLJS-867: extend-type with Object methods requires multi-arity style definition
* CLJS-1118: cljs.repl/doc support for protocols
* CLJS-889: re-pattern works on strings containing \u2028 or \u2029
* CLJS-109: Compiler errors/warnings should be displayed when cljs namespace 'package' names start with an unacceptable javascript symbol
* CLJS-891: Defs in "parent" namespaces clash with "child" namespaces with the same name?
* CLJS-813: Warn about reserved JS keyword usage in namespace names
* CLJS-876: merged sourcemap doesn't account for output-wrapper
* CLJS-1062: Incorrect deftype/defrecord definition leads to complex error messages
* CLJS-1120: analyze-deps does not appear to work when analyzing analysis caches
* CLJS-1119: constant table emission logic is incorrect
* CLJS-977: implement IKVReduce in Subvec
* CLJS-1117: Dependencies in JARs don't use cached analysis
* CLJS-689: js/-Infinity munges to _Infinity
* CLJS-1114: browser REPL script loading race condition
* CLJS-1110: cljs.closure/watch needs to print errors to *err*
* CLJS-1101 cljs.test might throw when trying to detect file-and-line
* CLJS-1090: macros imported from clojure.core missing docs
* CLJS-1108: :modules :output-to needs to create directories
* CLJS-1095: UUID to implement IComparable
* CLJS-1096: Update js/Date -equiv and -compare semantics based on Date.valueOf() value
* CLJS-1102 clojure.test should print column number of exception when available

## 0.0-3058

### Enhancements
* browser REPL source mapping for Firefox, Safari, Chrome
* macro support in REPL special functions
* CLJS-897: AOT core.cljs CLJS-899: AOT cache core.cljs analysis
* CLJS-1078: Nashorn REPL should use persistent code cache
* CLJS-1079: add way to execute arbitrary fn upon watch build completion
* CLJS-1034: Support REPL-defined functions in stacktrace infrastructure
* source mapping for Rhino
* CLJS-1071: support symbol keys in :closure-defines
* CLJS-1014: Support Closure Defines under :none
* CLJS-1068: node target define
* CLJS-1069: Generic :jsdoc support
* CLJS-1030: add `cljs.repl/pst`
* add `cljs.repl/apropos`, `cljs.repl/find-doc`, `cljs.repl/dir`
* fix `cljs.analyzer.api/all-ns` docstring
* add `cljs.analyzer.api/ns-publics`
* CLJS-1055: cljs.repl/doc should support namespaces and special forms
* Add ClojureScript special form doc map
* CLJS-1054: add clojure.repl/source functionality to cljs.repl
* CLJS-1053: REPLs need import special fn

### Changes
* move :init up in cljs.repl/repl
* CLJS-1087: with-out-str unexpectedly affected by *print-newline*
* CLJS-1093: Better compiler defaults
* Bump deps latest Closure Compiler, Rhino 1.7R5, data.json 0.2.6, tool.reader 0.8.16
* more sensible error if cljs.repl/repl arguments after the first incorrectly supplied
* default REPLs to :cache-analysis true
* default :output-dir for Nashorn and Node REPLs
* change ES6 Map `get` support to take additional `not-found` parameter
* deprecate clojure.reflect namespace now that REPLs are significantly enhanced, static vars, etc.

### Fixes
* stop blowing away cljs.user in browser REPL so REPL fns/macros remain available
* CLJS-1098: Browser REPL needs to support :reload and :reload-all
* CLJS-1097: source map url for AOTed cljs.core is wrong
* CLJS-1094: read option not used by cljs.repl/repl*
* CLJS-1089: AOT analysis cache has bad :file paths
* CLJS-1057: Nashorn REPL should not use EDN rep for errors
* CLJS-1086: Keyword constants should have stable names
* CLJS-964: Redefining exists? does not emit a warning like redefining array? does.
* CLJS-937: local fn name should be lexically munged
* CLJS-1082: analysis memoization bug
* CLJS-978: Analysis caching doesn't account for constants table
* CLJS-865: remove `cljs.js-deps/goog-resource` hack
* CLJS-1077: analyze-deps infinite recursive loop
* manually set *e in Rhino on JS exception
* REPL options merging needs to be more disciplined
* CLJS-1072: Calling .hasOwnProperty("source") in Clojurescript's string/replace will break with ES6
* CLJS-1064: ex-info is not printable
* Fix REPLs emitting code into .repl directory
* CLJS-1066: Rhino REPL regression
* be more disciplined about ints in murmur3 code
* Node.js REPL should work even if :output-dir not supplied
* Nashorn environment doesn't supply console, setup printing correctly

## 0.0-2913
* Support custom :output-to for :cljs-base module

## 0.0-2911

### Enhancements
* CLJS-1042: Google Closure Modules :source-map support
* CLJS-1041: Google Closure Modules :foreign-libs support
* Google Closure Modules support via :modules
* CLJS-1040: Source-mapped script stack frames for the Nashorn repl

### Changes
* CLJS-960: On carriage return REPLs should always show new REPL prompt
* CLJS-941: Warn when a symbol is defined multiple times in a file
* REPLs now support parameterization a la clojure.main/repl
* all REPLs analyze cljs.core before entering loop
* can emit :closure-source-map option for preserving JS->JS map
* REPLs can now merge new REPL/compiler options via -setup

### Fixes
* CLJS-998: Nashorn REPL does not support require special fn
* CLJS-1052: Cannot require ns from within the ns at the REPL for reloading purposes
* CLJS-975: preserve :reload & :reload-all in ns macro sugar
* CLJS-1039: Under Emacs source directory watching triggers spurious recompilation
* CLJS-1046: static vars do not respect user compile time metadata
* CLJS-989: ClojureScript REPL loops on EOF signal
* fix DCE regression for trivial programs
* CLJS-1036: use getResources not findResources in get-upstream-deps*

## 0.0-2850

### Enhancements
* CLJS-1035: REPLs should support watch recompilation

### Fixes
* CLJS-1037: cls.analyzer/ns-dependents fails for common cases

## 0.0-2843

### Enhancements
* CLJS-1032: Node.js target should support :main
* require cljs.test macro ns in cljs.test to get macro inference goodness
* include :url entries to original sources in mapped stacktraces if it can be determined   from the classpath
* support custom mapped stacktrace printing
* provide data oriented stacktrace mapping api
* CLJS-1025: make REPL source mapping infrastructure generic
* CLJS-1010: Printing hook for cljs-devtools
* CLJS-1016: make "..." marker configurable

### Changes
* CLJS-887: browser repl should serve CSS
* CLJS-1031: Get Closure Compiler over https in the bootstrap script

### Fixes
* cljs.nodejscli ns needs to set `goog.global` when `COMPILED` is true, this fixes the fundamental issues for ASYNC-110
* CLJS-967: "java.net.ConnectException: Connection refused" when running node repl
* pass relevant source map options in the incremental compile case
* add some missing source-map customization flags to optimized builds
* fix missed Rhino REPL regression, the surrounding REPL infrastructure creates cljs.user for us
* util.print has been deprecated in Node.js v0.12. Switch to console.log in Node.js REPLs.
* change `cljs.closure/watch` so it correctly watches all subdirectories do not recompile unless changed path is a file with .cljs or .js extension

## 0.0-2816

### Fixes
* CLJS-1001: reify did not elide reader metadata

## 0.0-2814

### Enhancements
* add simple source directory `cljs.closure/watch` watcher using java.nio
* CLJS-1022: Concatenate foreign dependencies safely
* CLJS-988: Support async testing in cljs.test
* CLJS-1018: Add support for cljs.core/*e Modify the JavaScript that is sent for evaluation to wrap in a try and then catch any exception thrown, assign it to *e, and then rethrow.
* CLJS-1012: Correct behavior when *print-length* is set to 0
* Added new :closure-extra-annotations compiler option allowing to define extra JSDoc annotation used by closure libraries.
* Mirrored source map support APIs on server/client
* Unified source mapping support in REPLs
* Nashorn REPL (thanks Pieter van Prooijen)

### Fixes
* CLJS-1023: regression, macro-autoload-ns? and ns-dependents need to throw on cyclic dependencies
* fix require with browser REPL, set base path to "goog/"
* CLJS-1020: off by one error in REPL source map support
* Node.js 0.12 support
* browser REPL needs to respect :output-dir
* CLJS-1006: Implicit dependency of clojure.browser.repl on cljs.repl
* CLJS-1005: Browser REPL creates 'out' directory no matter what
* CLJS-1003: fix cljs.test run-tests do-report :summary issues
* CLJS-1003: Cannot pass custom env to run-tests
* Windows Node.js REPL issues

## 0.0-2760

### Fixes
* ns spec handling regression

## 0.0-2758

### Fixes
* fix autoload macro enhancement

## 0.0-2755

### Enhancements
* CLJS-948: simplify macro usage

### Fixes
* CLJS-927: real incremental compilation
* Browser REPL regressions
* CLJS-991: Wrong inference - inconsistent behavior?
* CLJS-993: binding macro returns non-nil with empty body
* CLJS-972: Node.js REPL eats errors in required ns when using require
* CLJS-986: Add :target to the list of build options that should trigger recompilation
* CLJS-976: Node REPL breaks from uncaught exceptions

## 0.0-2740

### Changes
* local :foreign-libs can precisely override upstream :foreign-libs
* :foreign-libs :file-min is only used under :advanced optimizations
* file generated by supplying :main now idempotent
* more informative error if :main incorrectly supplied

### Fixes
* many fixes around file/resource handling for Windows users

## 0.0-2727

### Fixes
* Allow :main script imports to be configured via :asset-path

## 0.0-2725

### Fixes
* Fix Node.js support regression

## 0.0-2723

### Enhancements
* CLJS-851: simplify :none script inclusion if :main supplied
* CLJS-983: make ExceptionInfo printable

### Fixes

## 0.0-2719

### Changes
* CLJS-985: make ex-info not lose stack information
* CLJS-984: Update Node.js REPL support to use public API
* CLJS-963: do not bother computing goog/dep.js under :none

### Fixes
* CLJS-982: Var derefing should respect Clojure semantics
* CLJS-980: ClojureScript REPL stacktraces overrun prompt in many cases
* CLJS-979: ClojureScript REPL needs error handling for the special functions
* CLJS-971: :reload should work for require-macros special fn
* CLJS-936: Multi arity bitwise operators
* CLJS-962: fix inconsistent hashing of empty collections

## 0.0-2665

### Changes
* REPL -setup now must take opts
* CLJS-916: Optimize use of js-arguments in array and variadic
functions
* special case `'cljs.core/unquote`
* CLJS-945: Compile core with :static-fns true by default
* CLJS-958: Node.js REPL: Upon error, last successfully item printed

## 0.0-2657

### Changes
* Add require-macros REPL special fn

## 0.0-2655

### Changes
* add defonced cljs.core/*loaded-libs* dynamic var
* cljs.core/*print-fn* is now defonced
* throw on (var foo) when foo is not defined
* cljs.analyzer.api/resolve matches cljs.core/resolve if
  var doesn't exist return nil

### Fixes
* require needs to respect Clojure semantics, do not
  reload unless requested
* add ns/require support for :reload & :reload-all

## 0.0-2644

### Fixes
* CLJS-953: require REPL special fn can only take one argument
* CLJS-952: Bad type hinting on bit-test
* CLJS-947: REPL require of goog namespaces does not work
* CLJS-951: goog.require emitted multiple times under Node.js REPL
* CLJS-946: goog.require in REPLs will not reload recompiled libs
* CLJS-950: Revert adding compiled-by string to CLJS deps file
* CLJS-929: Minor fixes to test script
* CLJS-946: goog.require in REPLs will not reload recompiled libs

## 0.0-2629

### Enhancements
* Add Node.js REPL
* REPLs can now reuse build/analysis caching
* in-ns, require, doc support in REPLs

### Changes
* add :verbose flag to compiler to output compiler activity
* add *load-macros* to cljs.analyzer to optionally disable macro loading
* errors during ns parsing always through
* `cljs.util/compiled-by-version` needs to always return String
* pin Closure Compiler in bootstrap script
* refactor cljs.build.api namespace

### Fixes
* add cljs.test/are macro
* CLJS-931 : cljs.compiler/requires-compilation? ignores changes to build options
* CLJS-943: REPL require special fn is brittle
* CLJS-941: Warn when a symbol is defined multiple times in a file
* CLJS-942: Randomized port for Node.js REPL if port not specified
* CLJS-675: QuickStart example not working properly
* CLJS-935: script/noderepljs leaves node running after exit
* CLJS-918: preserve :arglists metadata in analysis cache
* CLJS-907: False positives from arithmetic checks
* CLJS-919 compare-and-set! relies on Atom record structure instead of protocols
* CLJS-920 add-watch/remove-watch should return reference, as in Clojure
* CLJS-921: cljs.repl/doc output includes namespace twice

## 0.0-2511

### Enhancements
* analysis caching via :cache-analysis build flag

## 0.0-2505

### Changes
* Stop generating random files for IJavaScript Strings
* added :source-map-timestamp build flag to get cache busting source
  map urls
* Enhancements to bootstrap script
* Stop warning about deps.cljs usage

### Fixes
* Fix Node.js source mapping regression introduced by commit 254e548
* CLJS-914: thrown-with-msg? is unable to get message of exception
* CLJS-915: On empty call, List and PersistentQueue do not retain meta, sorted-set/sorted map do not retain comparator

## 0.0-2498

### Fixes
* Support cljs.test/use-fixtures

## 0.0-2496

### Enhancements
* cljs.test added, mirrors clojure.test
* New cljs.analyzer.api namespace for easier access to analysis info from macros
* New cljs.analyzer.api namespace for easier access to analysis info from macros
* Support :test metadata on vars
* Support static vars
* cljs.source-map for client side source mapping
* expose ClojureScript :warnings build option
* CLJS-909: Add stable api for consumers of compiler data.

### Changes
* convert all ClojureScript tests to cljs.test
* add volatile! from Clojure 1.7
* stateful transducers use volatile!
* added `js-debugger` macro, compiles to "debugger;"
* CLJS-892: Improve performance of compare-symbols/compare-keywords
* CLJS-696: remove arguments usage from defrecord constructor
* unroll `partial`, copy & pasted from Clojure core.clj
* optimize clojure.string/join

### Fixes
* fix `cljs.nodejs/enable-util-print!`, incorrectly monkey patched `cjls.core/string-print` instead of setting `cljs.core/*print-fn*`
* cljs.reader bug, '/ incorrectly read
* avoid emitting the same goog.require

## 0.0-2411

### Enhancements
* forcing source maps to load for dynamic js reloads
* All ISeqable types are now ES6 iterable
* CLJS-863: Invalid arity error when calling 0-arity multimethod
* CLJS-622: better error reporting for zero arity protocol methods
* CLJS-506: expose more Closure minification knobs

### Changes
* CLJS-807: Emitter cannot emit BigInt or BigDecimal
* CLJS-749: Ignore .repl-* given that CLJS version is appended by default.
* CLJS-749: Append CLJS version to browser repl-env
* CLJS-749: *clojurescript-version* is unbound return empty string
* implement INamed for multi-method
* revert CLJS-801
* CLJS-888: Omit redundant {} around emitted recur
* CLJS-888: Better placement of newlines in emitter
* Join preambles with newline line to catch cases with files without newlines.
* add js-in interop macro
* Add nthrest
* CLJS-510: Throw error when :output-wrapper and :optimizations :whitespace combined
* CLJS-875: bump tools.reader dep to 0.8.10
* CLJS-879: add `update` from Clojure 1.7
* CLJS-857: change deftype*/defrecord* special forms to include their inline methods decls

### Fixes
* CLJS-885: relax type inference around numbers
* fix var resolution bug pointed out by Brandon Bloom
* CLJS-853: propagate read-time metadata on fn and reify forms at runtime
* CLJS-716: support hashing of JavaScript dates
* CLJS-814: clojure.string/reverse breaks surrogate pairs
* Recursively check IEncodeClojure in js->clj
* CLJS-873: non-higher-order calls to array-map should return PAMs
* CLJS-881: check for duplicate keys in array-map
* select-keys did not preserve metadata

## 0.0-2371

### Fixes
* CLJS-862: fix inconsistent re-pattern
* CLJS-866: Faulty ns macro desugaring
* CLJS-869: When preamble is not found in source directory, compiler does not report it

## 0.0-2356

### Fixes
* fix var analysis so that some.ns/foo.bar is handled correctly
* CLJS-854: cljs.reader could not read numbers under IE8

## 0.0-2342

### Changes
* depend on tools.reader 0.8.9

## 0.0-2341

### Enhancements
* transducers

### Fixes
* CLJS-704: warn if protocol extended to type multiple times in extend-type
* CLJS-702: warn if protocol doesn't match declared
* CLJS-859: use https for the bootstrap script
* CLJS-855: combinatorial code generation under advanced
* CLJS-858: resolve-existing var does not check vars outside current ns
* CLJS-852: same group-by as Clojure
* CLJS-847: Safari toString fix
* CLJS-846: preserve namespace metadata

## 0.0-2322

### Fixes
* CLJS-839: Mobile Safari Math.imul issue
* CLJS-845: incorrect behavior of `sequence` when given multiple collections
* count check in equiv-sequential if both arguments are ICounted
* only keep the param names when storing :method-params instead of the
  entire param AST
* preserve var metadata for deftype* and defrecord*
* preserve var metadata when creating deftype/record factory fns
* CLJS-831: Extending EventType to js/Element breaks Nashorn

## 0.0-2311

### Fixes
* fix typo which broke browser REPL
* lazier seq iterators a la CLJ-1497

## 0.0-2307

### Enhancement
* Allow multi-arity anonymous fns to optimize

## 0.0-2301

### Changes
* transducers

### Fixes
* eliminate dead branches in conditionals to prevent Closure warnings
* bad var resolution if when local contained .

## 0.0-2280

### Changes
* depend on latest org.clojure/google-closure-library

### Fixes
* fix constants table bug where keywords did not include precomputed hash-code

## 0.0-2277

## Enhancements
* All IEquiv implementor now export equiv Object method

## Fixes
* CLJS-824: Unsigned hash for keywords produced via keyword fn
* CLJS-827: CLJS-827: wrap macro expansion in try/catch
* CLJS-826: fix broken closure release script
* CLJS-825: conflict between node js support files
* typo in unchecked-subtract-int

## 0.0-2268

### Changes
* Experimental support for ES6 Map/Set interface

### Fixes
* CLJS-823: use non-native imul in Safari
* CLJS-810: re-matches returns [] if string is nil

## 0.0-2261

### Changes
* Dependency on Clojure 1.6.0

### Enhancements
* Murmur3 hashing for collections

### Fixes
* CLJS-817: Warning on use of undeclared var when creating recursive definition
* CLJS-819: cljs.reader cannot handle character classes beginning with slashes in regex literals
* CLJS-820: Missing invoke without arguments in MetaFn
* CLJS-816: clojure.set/rename-keys accidentally deletes keys

## 0.0-2234

### Fixes
* CLJS-812: Recur from case statement generated invalid JavaScript
* CLJS-811: use the correct class loader in cljs.js-deps/goog-resource
* fix fns with metadata under advanced compilation
* CLJS-809: dissoc :file metadata introduced by tools.reader 0.8.4
* mark cljs.reader vars as ^:dynamic to avoid compiler warnings
