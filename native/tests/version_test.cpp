#include <gtest/gtest.h>

#include "core/version.h"

TEST(Version, MatchesCMakeProjectVersion) { EXPECT_EQ(retrieve::version(), "0.1.0"); }
