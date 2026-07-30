package com.mineg.mobile.app

/**
 * Visual-only sample assets exported with the approved Stitch prototype.
 * These URLs are intentionally isolated from domain models and can be replaced
 * by real media URLs when the repository layer is connected.
 */
object MockVisualAssets {
  data class Crop(val assetPath: String, val x: Int, val y: Int, val width: Int, val height: Int)

  private const val privateOverview = "03-private-space/01-private-space-overview/reference.png"
  val mediaCrops = listOf(
    Crop(privateOverview, 20, 16, 101, 97),
    Crop(privateOverview, 124, 16, 103, 97),
    Crop(privateOverview, 231, 16, 102, 97),
    Crop(privateOverview, 20, 119, 101, 100),
    Crop(privateOverview, 124, 119, 103, 100),
    Crop(privateOverview, 231, 119, 102, 100),
    Crop(privateOverview, 20, 225, 101, 103),
    Crop(privateOverview, 124, 225, 103, 103),
    Crop(privateOverview, 231, 225, 102, 103),
  )

  // Use the unobstructed top half of the reference photo; dynamic status copy is drawn by Compose.
  val backupHero = Crop("06-backup/03-backup-uploading/reference.png", 20, 122, 313, 104)
  val profileAvatar = Crop("08-profile/01-profile-overview/reference.png", 130, 114, 92, 92)

  val media = listOf(
    "https://lh3.googleusercontent.com/aida-public/AB6AXuArwo2X4iddNZtJ2on7lpxk5jA_PuyQXRnNcSjGhqedUuFSm32v3EUz4xtFF_Dn7gpa5dY2Jje8B5g30MkmU_ePMELUwmNEwH4XYaBYX-h65uzefZSQ2q8fTOTmu-Dv23ihKJHtRrjoOTG7xv1OoupABnxpi2Z5F5GbhOn6lzYfFizGo4iO9eZyi-rk0_2mwvUDmOsJnodSQJkd-qHlrlLf80W-5MDJxflD9D7G7yq-GwEb9-bUn-Qi6sB9Yuo2OcbS3KSzLfwuxMA",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuAWNCXh0rJ33eQrDBuanQ8s7Wi5IVXxe6YTgEjJzaZg98CKHVXux6GqUFC4ec_rqPidzW8IuJIcAyBbpoGJb5Lj6wsBqyG9-1DCLZu3CmElq-ItR1ZfsuagThxdZ85K-2eflq-rViIM5KaHSvPVha3RBzaJhKlj8xzUxh48OkV6LmFul23HdB1JEOutOp5XMt78bOinQJQiOSbWZBIU3aqkUSS8VMKSBpVNLfkhKyp2PxhS8adWzLZqGJMkFsAN4XdAF2-aSu1ZmoA",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuA4nkCzBOw4n-QlVRQqGDyODq64UqkcIGwl1qNzlu5jhiXesCBZEDB5-JJyqU8ygAob5FJSvWUaX1elToE2Dk4ybwtZhRPcLIKFBj77RBW2i3js3CUiLjoEjiohJ1ci4Ncadgz4U9NYph0PD6GYzRMowqkn5PS4Su1b8JzFCMA8FEL-GVTTnmQBxWwDFYJSP3OsdL0ra2K6ZtWY3cilbFG89PgTpFO8vp-5qfo7mfSmvStMoq7kf-Pj-g2iY8LBVPDaCQCwz5wbICc",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuDP-0BN14hGrg-KEWffqDQjPBBYcSzUf8wzAK-5rht2-kay_CLXLUTFalpYZlivQdVxmDwje6UoexmNYSQpb9bbRJQFkpfn9jq9WvlH4k2xuJK7ChWQLGbX7nmzbnGRZokFTi8CkRO3xKplp8Qmtg9FaMjudL96PNjyWbfVYSoe1hpH3a-gXTrVGNbIMwtrzPQ7fqKE7xgcUylkrPFihlI08NSeMoYpmuotBeg3urSPu0TbZ2FT4ZCpd_FnElujY2Tnxj5NUP6Sk-4",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuB5RXu9DwDuT999ZWExNgneXvWTAsC8l2Gns1HBqD3bEP57CGe4jI7KS4Y3Y-mGcqKFcJE4cD9tAquLIK4hGvcA2cUwdrLCgrQ4znSXcCAX7Wd6wmkFec3i07LXgLZvvglauhJK9w2E5sWUamE7Ydgen0SNw4WQ0wiZpC0qdjtdhWTR0c-tRUJvAo4LwKx_h-3ARYdulaNJJEVlPQ8NqGVt1vrPp_lkGHTNcd2d73fj6tx_k-NSS3oVU7XzWra1AEdlzaRmAp51idA",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuCEhXj5EZdTPyY24tiYumI2VEcjQcLbQV8cwBozTWV8ipp7Gn_ToAmd3vKKDQDtR2O-2f1BjDf5jljuclUKKEg8LNwy58BnlAKy8oPMW42Z3rUfxitIto0Y5bdLasv_-6aWf73giKhoQ1UFOP1rSik23GvUkdVYk9d15RlhLDXOxCpq0HD0nX8YIfuVdkp24L8i9hrMuNTZaXFXsUwfCtOWRjWMEywGFXljMEraBgprNvbNp5GU3yA61WoVE3IBjlPypNtWQv5adIg",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuAe94fX7aB-6R6skjnOqFJTcXK5c09v63cKis4nU1wxiurb5SxYouz8dD53-gjCh1jXESC2iOzwL85rujwv_cxvfEdyLy1viue-H5HvUpte58mbupy09WdX2eXGxl8QuB329FlB1b0L0x4evQ-L0l9dh6BwtEkrleN5zWZ38ojEBjoO5fXY5_bQCXKdGMd5Fj3sZ4dB-zJSNEnw6IrkpP_bGmgJELpanJcfE2g6eUYIMLFJzUFnkofEr4INuxYi07b1cPj__e481UY",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuDZntQAOvhOdScNjcoiVo7crHISC9RvOFqTirzB90bSNdG7QjYH4Lvc-pYu5JKNnFNvF4S6PBGn0dVoH-mxwIC4TFgoqAtkTWM2t5hmpXt_M_CPinFG3HBRANnRA-OJafr39dM2Ss7aJNdphrypms5KlR2XyGWbbXEAfxPUZlba7coneiT1apCg4EfkCdJdEvObRoRj-ro5RQWqbuumukQNLDYz3nC2KdwO25Jf0x44kc0UJ-GUIe1q_1DGLSw_bFhgAnqw-QMQayA",
    "https://lh3.googleusercontent.com/aida-public/AB6AXuAyOcl0nf_2kHXh7AyrC_TmoKhHF74t_oP5tksf7TSQpYGGCR9WDRNtDWtrmph8d7-3URUXL8xzVHe_RersHGWgSpZixgDEuIF2hSN2mkQYV79q8ZUCDsulGWb66b-F8DyCHfhY1UodNKq-VxkzysoTiOuplf829lPFGkPH4vwI_o3Qm1LhNxcwrgi24Sui0vOQvAphkFPFsCq7J4iSv78-IAtupvJnQ-Pyb5xOY-qNgP6Jx3nofPox48jWF5I9pyvdjis56QvLnd4",
  )

  const val profileAvatarUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuBwj_G4bzCQnl-rfLSXZTJuYZDGogkvvjnljhwS0fU5u61bcAN_CwHO5xr1LgMxmZgFOctFxj9ssbIx3T1ECfD-JVKEnZqWV1vsEqx7hDm9FkvqBXb8SQ1v-yJ7iE0SyQ7UqlJVkqtezfl4FcMlGchuCr-Wf5fdZDIuZRx73cw7FMCC_2Y91c7k75Kg4BmlkrGzU3BHg9VSpsOVvlkEFOVzv8FPkq3x3EnwFCctEf0YGoi_NsdbnrvC7YBuEkn_vAsyYd4U-awqWFU"
}
