#!/usr/bin/env bash
set -euo pipefail

echo "=== Verifying Localization Refresh Coverage across all surfaces ==="

# Check that every surface ViewModel observes localizationConfig
VIEWMODELS=(
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/search/SearchViewModel.kt"
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/anime/AnimeExploreViewModel.kt"
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/manga/MangaExploreViewModel.kt"
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/season/SeasonAnimeViewModel.kt"
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/charts/MediaChartViewModel.kt"
    "feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/recommendations/RecommendationsViewModel.kt"
    "feature/characterdetails/src/main/java/com/axiel7/anihyou/feature/characterdetails/CharacterDetailsViewModel.kt"
    "feature/staffdetails/src/main/java/com/axiel7/anihyou/feature/staffdetails/StaffDetailsViewModel.kt"
    "feature/mediadetails/src/main/java/com/axiel7/anihyou/feature/mediadetails/MediaDetailsViewModel.kt"
    "feature/mediadetails/src/main/java/com/axiel7/anihyou/feature/mediadetails/activity/MediaActivityViewModel.kt"
    "feature/mediadetails/src/main/java/com/axiel7/anihyou/feature/mediadetails/characters/MediaCharactersViewModel.kt"
    "feature/usermedialist/src/main/java/com/axiel7/anihyou/feature/usermedialist/UserMediaListViewModel.kt"
    "feature/calendar/src/main/java/com/axiel7/anihyou/feature/calendar/CalendarViewModel.kt"
    "feature/home/src/main/java/com/axiel7/anihyou/feature/home/current/CurrentViewModel.kt"
    "feature/home/src/main/java/com/axiel7/anihyou/feature/home/activity/ActivityFeedViewModel.kt"
    "feature/profile/src/main/java/com/axiel7/anihyou/feature/profile/ProfileViewModel.kt"
    "feature/profile/src/main/java/com/axiel7/anihyou/feature/profile/favorites/UserFavoritesViewModel.kt"
    "feature/studiodetails/src/main/java/com/axiel7/anihyou/feature/studiodetails/StudioDetailsViewModel.kt"
)

MISSING=0
for vm in "${VIEWMODELS[@]}"; do
    if ! grep -q "localizationConfig" "$vm"; then
        echo "FAIL: $vm does NOT observe localizationConfig!"
        MISSING=$((MISSING + 1))
    else
        echo "OK: $vm observes localizationConfig"
    fi
done

if [ "$MISSING" -ne 0 ]; then
    echo "FAILED: $MISSING ViewModels missing localizationConfig handling!"
    exit 1
fi

echo "=== All 18 localized GraphQL surfaces observe localizationConfig! ==="
