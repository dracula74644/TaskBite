"""Curated reuse of complete human performances; no synthesized or relabeled speech."""
import copy


def extend_standard(categories):
    original = copy.deepcopy(categories)

    def pool(*groups):
        clips = [clip for kind, count in groups for clip in original[kind][:count]]
        return list({clip['asset']: clip for clip in clips}.values())

    # Repeated reminders need a call to action or playful disapproval, not
    # "Welcome. Good luck." or another greeting. Use intact recorded phrases.
    categories['PLAN_DAY'] = pool(('TINY', 1), ('CREATED', 1), ('STARTED', 1), ('SNOOZE', 2))
    categories['IDLE'] = pool(('TINY', 1), ('SNOOZE', 2), ('CREATED', 1), ('STARTED', 1))
    categories['ABANDON'] = pool(('SNOOZE', 2), ('TINY', 1), ('CREATED', 1), ('STARTED', 1))
    # A dismissal gets playful disapproval, not a greeting or starting countdown.
    categories['NOTIFICATION_CLEARED'] = pool(('SNOOZE', 2))
    return categories


def extend_game(categories):
    original = copy.deepcopy(categories)
    start = original['STARTED'][0]
    stall = original['IDLE'][0]
    approve = original['CREATED'][0]
    complete = original['COMPLETED'][0]
    for kind in ('PLAN_DAY', 'OVERDUE', 'TINY'):
        categories[kind] = [start, stall]
    for kind in ('IDLE', 'ABANDON', 'SNOOZE'):
        categories[kind] = [stall, start]
    categories['CREATED'] = [approve, start]
    categories['STARTED'] = [start, approve]
    categories['COMPLETED'] = [complete, approve]
    categories['NOTIFICATION_CLEARED'] = [stall, start]
    return categories


if __name__ == '__main__':
    import json
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    for path, extend in (
        (root / 'app/src/main/assets/voices/catalog.json', extend_standard),
        (root / 'app/src/personal/assets/voices/high_on_life/catalog.json', extend_game),
    ):
        if not path.exists():
            continue
        catalog = json.loads(path.read_text())
        for actor in catalog['actors']:
            actor['categories'] = extend(actor['categories'])
        path.write_text(json.dumps(catalog, indent=2) + '\n')
        print(path.relative_to(root))
