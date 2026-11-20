"""Keep generic acknowledgements in the actions they can accurately describe."""
import copy


def extend_standard(categories):
    return {kind: copy.deepcopy(categories[kind]) for kind in ("CREATED", "STARTED", "COMPLETED")}


def extend_game(categories):
    return extend_standard(categories)


if __name__ == "__main__":
    import json
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    for path in (root / "app/src/main/assets/voices/catalog.json", root / "app/src/personal/assets/voices/high_on_life/catalog.json"):
        if not path.exists():
            continue
        catalog = json.loads(path.read_text())
        for actor in catalog["actors"]:
            actor["categories"] = extend_standard(actor["categories"])
        path.write_text(json.dumps(catalog, indent=2) + "\n")
        print(path.relative_to(root))
