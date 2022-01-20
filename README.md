# Proactive Energy-Aware Adaptive Video Streaming on Mobile Devices

This codebase implements the system described in the paper:

 >Proactive Energy-Aware Adaptive Video Streaming on Mobile Devices
 >
 >[Jiayi Meng](https://www.cs.purdue.edu/homes/meng72/), Qiang Xu, Y. Charlie Hu

## How to run Puffer's server with the energy-aware proactive ABR algorithm?

### Step 1: Clone and compile Puffer

1. Clone [Puffer](https://github.com/StanfordSNR/puffer).

2. Compile Puffer by following its [documentation](https://github.com/StanfordSNR/puffer/wiki/Documentation).

### Step 2: Add proactive energy-aware ABR algorithm to Puffer

1. Enter the `Server` directory.

2. Copy `abr/mpc_proactive_sim.hh` and `abr/mpc_proactive_sim.cc` to Puffer's `src/abr`. 

NOTE: put the pre-trained power models for network and video decoding and displaying in `abr/mpc_proactive_sim.hh` and `abr/mpc_proactive_sim.cc` respectively.

3. Add two files above to Puffer's `src/media-server/Makefile.am` to compile Puffer with the newly added energy-aware proactive algorithm. 

4. Enable puffer to return client's playback information back to the energy-aware proactive algorithm and register the new algorithm, by copying files under the `media-server` directory in this repo to the directory of Puffer's `src/media-server/`.

More details about adding new algorithm can be found [here](https://github.com/StanfordSNR/puffer/wiki/Documentation#how-to-add-your-own-abr-algorithm).

5. Compile puffer.

6. Copy `settings.yml` to `<puffer>/src`. Run puffer with `settings.yml`, using the command below. We provide one `settings.yml` in the repo as one example.

Put your custom `max_power_budget` in `settings.yml`.

```
cd <puffer>
./src/media-server/run_servers src/settings.yml
```

More details about the settings can be found [here](https://github.com/StanfordSNR/puffer/wiki/Documentation#about-settingsyml).

## How to run the client on mobile device?

1. Change the ip address of the server in `MainActivity.java`.

2. Build the android project under the `Client` directory in Android Studio.


## If you find our work useful in your research, please consider citing our paper

   @inproceedings{meng2021proactive,
     title={Proactive Energy-Aware Adaptive Video Streaming on Mobile Devices},
     author={Meng, Jiayi and Qiang, Xu and Hu, Y. Charlie},
     booktitle={2021 $\{$USENIX$\}$ Annual Technical Conference ($\{$USENIX$\}$$\{$ATC$\}$ 21)},
     year={2021}
   }
