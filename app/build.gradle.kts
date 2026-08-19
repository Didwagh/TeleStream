plugins {
        id("com.android.application")
            id("org.jetbrains.kotlin.android")
}

android {
        namespace = "com.example.tgserver"
            compileSdk = 34

                defaultConfig {
                            applicationId = "com.example.tgserver"
                                    minSdk = 24
                                            targetSdk = 34
                                                    versionCode = 1
                                                            versionName = "0.1"
                }

                    buildTypes {
                                debug {
                                                isMinifyEnabled = false
                                }
                    }

                        compileOptions {
                                    sourceCompatibility = JavaVersion.VERSION_17
                                            targetCompatibility = JavaVersion.VERSION_17
                        }
                            kotlinOptions {
                                        jvmTarget = "17"
                            }
}

                            }
                        }
                                }
                    }
                }
}
}