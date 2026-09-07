# General duplicate merge field selection

The general Android duplicate finder now offers a field-wise chooser when merging selected duplicate entries.

For every persisted field whose values differ between the selected entries, the merge sheet shows each available version and requires one version to be selected. Identical fields are retained automatically. When exactly one entry has a populated value for a field, that value is selected by default.

The resulting merged object is passed to the existing native `mergeBookEntries` implementation, so deletion of redundant entries and catalog persistence continue to use the existing merge path.
