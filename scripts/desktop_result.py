"""Read a Morphe Desktop --result-file in the 1.18 shape or the 1.19 shape.

1.18.1 writes PatchingResult: appliedPatches as {"name": ...} objects, failedPatches as
{"patch": {"name": ...}, "reason": ...} and the steps as patchingSteps. 1.19.0 writes
PatchEngine.Result: appliedPatches as plain names, failedPatches as {"name": ..., "error": ...}
and the steps as stepResults.
"""


def applied_names(report):
    """The applied patch names, in the order Desktop wrote them."""
    patches = report.get("appliedPatches") if isinstance(report, dict) else None
    if not isinstance(patches, list):
        raise ValueError("Desktop wrote no appliedPatches list")
    names = []
    for patch in patches:
        name = patch.get("name") if isinstance(patch, dict) else patch
        if not isinstance(name, str):
            raise ValueError("Desktop wrote an applied patch with no name")
        names.append(name)
    return names


def failures(report):
    """(name, reason) for each failed patch."""
    items = report.get("failedPatches") if isinstance(report, dict) else None
    if not isinstance(items, list):
        raise ValueError("Desktop wrote no failedPatches list")
    found = []
    for item in items:
        if isinstance(item, dict) and "patch" in item:
            patch = item["patch"]
            name = patch.get("name") if isinstance(patch, dict) else None
            reason = item.get("reason")
        elif isinstance(item, dict):
            name, reason = item.get("name"), item.get("error")
        else:
            name = reason = None
        if not isinstance(name, str) or not isinstance(reason, str):
            raise ValueError("Desktop wrote a failed patch with no name or reason")
        found.append((name, reason))
    return found


def steps(report):
    """The step list under either key, or None when Desktop wrote neither."""
    if not isinstance(report, dict):
        return None
    return report.get("stepResults", report.get("patchingSteps"))
